package com.loopers.infrastructure.outbox;

import com.loopers.domain.outbox.OutboxMessage;
import com.loopers.domain.outbox.OutboxStatus;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Manual additive DDL and actual MySQL constraints. Never connects to a user datasource. */
@Testcontainers
@Timeout(60)
class OutboxSchemaIntegrationTest {
    private static final Instant CREATED_AT = Instant.parse("2026-11-04T01:02:03.123456Z");
    private static final String PAYLOAD = "{ \"eventType\":\"OrderConfirmed\", \"schemaVersion\":1,"
        + " \"eventId\":\"order-confirmed:v1:42\", \"orderId\":42,"
        + " \"confirmedAt\":\"2026-11-04T01:02:00.123456Z\", \"items\":[{\"productId\":7,\"quantity\":2}] }";

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
        .withDatabaseName("w7_outbox_schema_test");

    private DataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeEach
    void appliesOnlyTheOwnedContainerSchema() {
        dataSource = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP TABLE IF EXISTS outbox_message");
        new ResourceDatabasePopulator(new ClassPathResource("db/schema/012-outbox.sql")).execute(dataSource);
    }

    @Test
    void validatesMappingAndRoundTripsTheExactSavedMessageAndInitialDeliveryState() {
        Long id;
        try (SessionFactory factory = validatedFactory()) {
            try (Session session = factory.openSession()) {
                var transaction = session.beginTransaction();
                OutboxMessage message = message("order-confirmed:v1:42");
                session.persist(message);
                transaction.commit();
                id = message.getId();
            }
            try (Session session = factory.openSession()) {
                OutboxMessage loaded = session.find(OutboxMessage.class, id);
                assertThat(loaded.getEventId()).isEqualTo("order-confirmed:v1:42");
                assertThat(loaded.getAggregateType()).isEqualTo("Order");
                assertThat(loaded.getAggregateId()).isEqualTo("42");
                assertThat(loaded.getEventType()).isEqualTo("OrderConfirmed");
                assertThat(loaded.getSchemaVersion()).isEqualTo(1);
                assertThat(loaded.getTopic()).isEqualTo("order-events");
                assertThat(loaded.getMessageKey()).isEqualTo("42");
                assertThat(loaded.getPayload()).isEqualTo(PAYLOAD);
                assertThat(loaded.getCreatedAt()).isEqualTo(CREATED_AT);
                assertThat(loaded.getStatus()).isEqualTo(OutboxStatus.PENDING);
                assertThat(loaded.getNextAttemptAt()).isEqualTo(CREATED_AT);
                assertThat(loaded.getAttemptCount()).isZero();
                assertThat(loaded.getPublishedAt()).isNull();
                assertThat(loaded.getLastError()).isNull();
            }
        }
        assertThat(jdbc.queryForObject("SELECT payload FROM outbox_message WHERE id=?", String.class, id)).isEqualTo(PAYLOAD);
    }

    @Test
    void rollsBackAnActuallyInsertedRowAndPreservesPreviouslyCommittedFacts() {
        try (SessionFactory factory = validatedFactory()) {
            persist(factory, "already-committed");
            var before = jdbc.queryForList("SELECT * FROM outbox_message");
            try (Session session = factory.openSession()) {
                var transaction = session.beginTransaction();
                session.persist(message("will-rollback"));
                session.flush();
                assertThat(session.createNativeQuery("SELECT COUNT(*) FROM outbox_message", Long.class)
                    .getSingleResult()).isEqualTo(2);
                transaction.rollback();
            }
            assertThat(jdbc.queryForList("SELECT * FROM outbox_message")).isEqualTo(before);
        }
    }

    @Test
    void concurrentTransactionsWithTheSameEventIdCommitOnlyOneUnchangedRow() throws Exception {
        try (SessionFactory factory = validatedFactory()) {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            var workers = Executors.newFixedThreadPool(2);
            try {
                var first = workers.submit(() -> concurrentInsert(factory, ready, start));
                var second = workers.submit(() -> concurrentInsert(factory, ready, start));
                assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
                start.countDown();
                assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("COMMITTED", "DUPLICATE");
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_message", Long.class)).isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT payload FROM outbox_message", String.class)).isEqualTo(PAYLOAD);
            } finally {
                start.countDown();
                workers.shutdownNow();
                assertThat(workers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    @Test
    void keepsCaseAndTrailingSpaceDistinctInTheUniqueEventIdentity() {
        try (SessionFactory factory = validatedFactory()) {
            persist(factory, "Event:42");
            persist(factory, "event:42");
            persist(factory, "event:42 ");
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_message", Long.class)).isEqualTo(3);
    }

    @Test
    void rejectsInvalidDeliveryAndJsonDataAtTheDatabaseBoundary() {
        try (SessionFactory factory = validatedFactory()) {
            persist(factory, "one");
        }
        var before = jdbc.queryForList("SELECT * FROM outbox_message");
        for (String assignment : List.of("schema_version=0", "attempt_count=-1", "payload='not-json'",
            "status='PUBLISHED'", "published_at=NOW(6)")) {
            assertThatThrownBy(() -> jdbc.update("UPDATE outbox_message SET " + assignment))
                .isInstanceOf(DataAccessException.class);
            assertThat(jdbc.queryForList("SELECT * FROM outbox_message")).isEqualTo(before);
        }
    }

    private String concurrentInsert(SessionFactory factory, CountDownLatch ready, CountDownLatch start) throws Exception {
        try (Session session = factory.openSession()) {
            var transaction = session.beginTransaction();
            ready.countDown();
            assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
            try {
                session.persist(message("same-event"));
                transaction.commit();
                return "COMMITTED";
            } catch (org.hibernate.exception.ConstraintViolationException duplicate) {
                if (transaction.isActive()) {
                    transaction.rollback();
                }
                assertThat(duplicate.getSQLException().getErrorCode()).isEqualTo(1062);
                assertThat(duplicate.getSQLException().getMessage()).contains("uq_outbox_event_id");
                return "DUPLICATE";
            }
        }
    }

    private void persist(SessionFactory factory, String eventId) {
        try (Session session = factory.openSession()) {
            var transaction = session.beginTransaction();
            session.persist(message(eventId));
            transaction.commit();
        }
    }

    private OutboxMessage message(String eventId) {
        return OutboxMessage.pending(eventId, "Order", "42", "OrderConfirmed", 1, "order-events", "42", PAYLOAD, CREATED_AT);
    }

    private SessionFactory validatedFactory() {
        Configuration configuration = new Configuration().addAnnotatedClass(OutboxMessage.class);
        configuration.getProperties().put("hibernate.connection.datasource", dataSource);
        configuration.setProperty("hibernate.hbm2ddl.auto", "validate");
        configuration.setProperty("hibernate.jdbc.time_zone", "UTC");
        return configuration.buildSessionFactory();
    }
}
