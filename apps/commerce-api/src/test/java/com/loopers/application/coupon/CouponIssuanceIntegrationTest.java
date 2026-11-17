package com.loopers.application.coupon;

import com.loopers.application.user.UserResolutionException;
import com.loopers.application.user.UserResolver;
import com.loopers.domain.coupon.CouponException;
import com.loopers.domain.coupon.CouponStatus;
import com.loopers.infrastructure.user.FixtureUserIdentityRepository;
import com.loopers.infrastructure.user.UserRepositoryImpl;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.LongFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(60)
class CouponIssuanceIntegrationTest {
    private static final long CAMPAIGN_ID = 42L;
    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
        .withDatabaseName("w8_coupon_issuance_test")
        .withCommand("--log-bin-trust-function-creators=1");

    private AnnotationConfigApplicationContext context;
    private CouponIssuanceService service;
    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;

    @BeforeAll
    void startIsolatedContext() {
        context = new AnnotationConfigApplicationContext(CouponDatabaseConfig.class);
        service = context.getBean(CouponIssuanceService.class);
        jdbc = new JdbcTemplate(context.getBean(DataSource.class));
        transactions = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @AfterAll
    void closeIsolatedContext() {
        if (context != null) {
            context.close();
        }
    }

    @BeforeEach
    void resetOwnedTables() {
        jdbc.update("DELETE FROM owned_coupon");
        jdbc.update("DELETE FROM coupon_reservation");
        jdbc.update("DELETE FROM coupon_campaign");
        jdbc.update("DELETE FROM `user`");
        jdbc.update("""
            INSERT INTO `user` (id, point_balance, created_at, updated_at)
            VALUES (1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)), (2, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
            """);
    }

    private void createCampaign(int quota) {
        createCampaign(CAMPAIGN_ID, quota);
    }

    private void createCampaign(long campaignId, int quota) {
        jdbc.update("""
            INSERT INTO coupon_campaign (id, name, active, total_quota, waiting_count, issued_count, benefit_description)
            VALUES (?, '행사', TRUE, ?, 0, 0, '5,000원 할인')
            """, campaignId, quota);
    }

    @Test
    void lastCouponGoesToExactlyOneOfTwoConcurrentUsers() throws Exception {
        createCampaign(1);
        List<Outcome> outcomes = race(1, 2, user -> service.reserve(CAMPAIGN_ID, user));
        assertThat(outcomes).filteredOn(result -> result.reason() == CouponException.Reason.SOLD_OUT).hasSize(1);
        var winners = outcomes.stream().filter(result -> result.reservation() != null).toList();
        assertThat(winners).hasSize(1);
        var winner = winners.getFirst().reservation();
        assertThat(winner.status()).isEqualTo(CouponStatus.WAITING);
        assertThat(winner.couponId()).isNull();
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM coupon_reservation
            WHERE id = ? AND campaign_id = ? AND user_id = ? AND status = 'WAITING'
            """, Integer.class, winner.reservationId(), CAMPAIGN_ID, winner.userId())).isOne();
        assertDatabaseCounts(1, 0);
    }

    @Test
    void repeatedConcurrentRequestsForOneUserKeepOneReservationEvenWhenSoldOut() throws Exception {
        createCampaign(1);
        var results = race(1, 1, user -> service.reserve(CAMPAIGN_ID, user));
        assertThat(results).allSatisfy(outcome -> {
            assertThat(outcome.reason()).isNull();
            assertThat(outcome.reservation().status()).isEqualTo(CouponStatus.WAITING);
        });
        assertThat(results.getFirst().reservation().reservationId()).isEqualTo(results.getLast().reservation().reservationId());
        assertDatabaseCounts(1, 0);
    }

    @Test
    void twoWorkersFinishingTheSameReservationReturnTheSameCouponAfterTheCampaignCloses() throws Exception {
        createCampaign(1);
        var held = service.reserve(CAMPAIGN_ID, 1);
        jdbc.update("UPDATE coupon_campaign SET active = FALSE WHERE id = ?", CAMPAIGN_ID);
        assertThat(service.reserve(CAMPAIGN_ID, 1).reservationId()).isEqualTo(held.reservationId());

        var results = race(1, 1, user -> service.issue(CAMPAIGN_ID, user));
        assertThat(results).allSatisfy(outcome -> {
            assertThat(outcome.reason()).isNull();
            assertThat(outcome.reservation().status()).isEqualTo(CouponStatus.ISSUED);
            assertThat(outcome.reservation().reservationId()).isEqualTo(held.reservationId());
            assertThat(outcome.reservation().couponId()).isNotNull();
        });
        var couponId = results.getFirst().reservation().couponId();
        assertThat(results.getLast().reservation().couponId()).isEqualTo(couponId);
        assertThat(service.reserve(CAMPAIGN_ID, 1).couponId()).isEqualTo(couponId);
        assertDatabaseCounts(0, 1);
    }

    @Test
    void failingSqlAfterReservationWritesRollsBackTheHoldAndAllowsTheOtherUserToReserve() {
        createCampaign(1);
        assertThatExceptionOfType(DuplicateKeyException.class).isThrownBy(() ->
            transactions.executeWithoutResult(tx -> {
                service.reserve(CAMPAIGN_ID, 1);
                flushChanges();
                assertDatabaseCounts(1, 0);
                insertDuplicateFixtureUser();
            }));
        assertDatabaseCounts(0, 0);
        assertThat(service.findReservation(CAMPAIGN_ID, "alice")).isEmpty();
        assertThat(service.reserve(CAMPAIGN_ID, 2).status()).isEqualTo(CouponStatus.WAITING);
        assertDatabaseCounts(1, 0);
    }

    @Test
    void failingSqlAfterIssuanceWritesPreservesTheCommittedReservationForWorkerRetry() {
        createCampaign(1);
        var held = service.reserve(CAMPAIGN_ID, 1);
        assertThatExceptionOfType(DuplicateKeyException.class).isThrownBy(() ->
            transactions.executeWithoutResult(tx -> {
                service.issue(CAMPAIGN_ID, 1);
                flushChanges();
                assertDatabaseCounts(0, 1);
                insertDuplicateFixtureUser();
            }));
        assertDatabaseCounts(1, 0);
        assertThat(service.findReservation(CAMPAIGN_ID, "alice").orElseThrow().reservationId()).isEqualTo(held.reservationId());

        var issued = service.issue(CAMPAIGN_ID, 1);
        assertThat(issued.reservationId()).isEqualTo(held.reservationId());
        assertThat(issued.couponId()).isNotNull();
        assertDatabaseCounts(0, 1);
    }

    @Test
    void failedOwnedCouponInsertRollsBackIssuanceAndCanRetryTheSameReservation() {
        createCampaign(1);
        var held = service.reserve(CAMPAIGN_ID, 1);
        jdbc.execute("""
            CREATE TRIGGER fail_owned_coupon_insert BEFORE INSERT ON owned_coupon FOR EACH ROW
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'injected owned_coupon insert failure'
            """);
        try {
            assertThatExceptionOfType(RuntimeException.class)
                .isThrownBy(() -> service.issue(CAMPAIGN_ID, 1))
                .satisfies(error -> assertThat(causedByInsertTrigger(error)).isTrue());
            assertDatabaseCounts(1, 0);
            var pending = service.findReservation(CAMPAIGN_ID, "alice").orElseThrow();
            assertThat(pending.reservationId()).isEqualTo(held.reservationId());
            assertThat(pending.status()).isEqualTo(CouponStatus.WAITING);
            assertThat(pending.couponId()).isNull();
        } finally {
            jdbc.execute("DROP TRIGGER fail_owned_coupon_insert");
        }

        var issued = service.issue(CAMPAIGN_ID, 1);
        assertThat(issued.reservationId()).isEqualTo(held.reservationId());
        assertThat(issued.status()).isEqualTo(CouponStatus.ISSUED);
        assertThat(issued.couponId()).isNotNull();
        assertDatabaseCounts(0, 1);
    }

    @Test
    void mysqlConstraintsRejectDuplicateReservationDuplicateCouponAndQuotaOverflow() {
        createCampaign(1);
        var held = service.reserve(CAMPAIGN_ID, 1);
        var issued = service.issue(CAMPAIGN_ID, 1);

        assertMysqlStatementRejected(1062, () -> jdbc.update("""
            INSERT INTO coupon_reservation (campaign_id, user_id, status) VALUES (?, 1, 'WAITING')
            """, CAMPAIGN_ID));
        assertMysqlStatementRejected(1062, () -> jdbc.update("""
            INSERT INTO owned_coupon (reservation_id, benefit_description, issued_at)
            VALUES (?, '중복 발급 시도', UTC_TIMESTAMP(6))
            """, held.reservationId()));
        assertMysqlStatementRejected(3819, () -> jdbc.update("""
            UPDATE coupon_campaign SET waiting_count = 1 WHERE id = ?
            """, CAMPAIGN_ID));

        assertDatabaseCounts(0, 1);
        var saved = service.findReservation(CAMPAIGN_ID, "alice").orElseThrow();
        assertThat(saved.reservationId()).isEqualTo(held.reservationId());
        assertThat(saved.couponId()).isEqualTo(issued.couponId());
        assertThat(saved.status()).isEqualTo(CouponStatus.ISSUED);
    }

    private void assertMysqlStatementRejected(int expectedErrorCode, Runnable statement) {
        assertThatExceptionOfType(DataAccessException.class).isThrownBy(statement::run)
            .satisfies(error -> assertThat(error.getCause()).isInstanceOfSatisfying(SQLException.class,
                sql -> assertThat(sql.getErrorCode()).isEqualTo(expectedErrorCode)));
    }

    @Test
    void missingAndInactiveCampaignsAndMissingReservationsDoNotChangeStoredCounts() {
        createCampaign(1);
        assertThatExceptionOfType(CouponException.class)
            .isThrownBy(() -> service.reserve(CAMPAIGN_ID + 1, 1))
            .satisfies(error -> assertThat(error.reason()).isEqualTo(CouponException.Reason.CAMPAIGN_NOT_FOUND));
        assertThatExceptionOfType(CouponException.class)
            .isThrownBy(() -> service.issue(CAMPAIGN_ID, 1))
            .satisfies(error -> assertThat(error.reason()).isEqualTo(CouponException.Reason.RESERVATION_NOT_FOUND));
        jdbc.update("UPDATE coupon_campaign SET active = FALSE WHERE id = ?", CAMPAIGN_ID);
        assertThatExceptionOfType(CouponException.class)
            .isThrownBy(() -> service.reserve(CAMPAIGN_ID, 1))
            .satisfies(error -> assertThat(error.reason()).isEqualTo(CouponException.Reason.INACTIVE));
        assertDatabaseCounts(0, 0);
    }

    @Test
    void campaignStatusAndOwnedListShowOnlyTheCurrentUsersCommittedRecords() {
        createCampaign(2);
        var held = service.reserve(CAMPAIGN_ID, 1);
        assertThat(service.findReservation(CAMPAIGN_ID, "alice").orElseThrow().reservationId()).isEqualTo(held.reservationId());
        assertThat(service.findReservation(CAMPAIGN_ID, "alice").orElseThrow().status()).isEqualTo(CouponStatus.WAITING);
        assertThat(service.findReservation(CAMPAIGN_ID, "bob")).isEmpty();
        assertThat(service.listIssued("alice", 0, 10)).isEmpty();

        var issued = service.issue(CAMPAIGN_ID, 1);
        assertThat(service.findReservation(CAMPAIGN_ID, "alice").orElseThrow().status()).isEqualTo(CouponStatus.ISSUED);
        assertThat(service.listIssued("alice", 0, 10)).singleElement().satisfies(coupon -> {
            assertThat(coupon.couponId()).isEqualTo(issued.couponId());
            assertThat(coupon.campaignId()).isEqualTo(CAMPAIGN_ID);
            assertThat(coupon.benefitDescription()).isEqualTo("5,000원 할인");
            assertThat(coupon.issuedAt()).isNotNull();
        });
        assertThat(service.findReservation(CAMPAIGN_ID, "bob")).isEmpty();
        assertThat(service.listIssued("bob", 0, 10)).isEmpty();
        assertThatExceptionOfType(UserResolutionException.class)
            .isThrownBy(() -> service.findReservation(CAMPAIGN_ID, "unknown"))
            .satisfies(error -> assertThat(error.getReason()).isEqualTo(UserResolutionException.Reason.USER_NOT_FOUND));
        assertThatExceptionOfType(UserResolutionException.class)
            .isThrownBy(() -> service.listIssued("unknown", 0, 10))
            .satisfies(error -> assertThat(error.getReason()).isEqualTo(UserResolutionException.Reason.USER_NOT_FOUND));
    }

    @Test
    void issuedListOrdersByTimeDescendingThenCouponIdDescendingAcrossPages() {
        createCampaign(1);
        createCampaign(CAMPAIGN_ID + 1, 1);
        createCampaign(CAMPAIGN_ID + 2, 1);
        var couponIds = List.of(CAMPAIGN_ID, CAMPAIGN_ID + 1, CAMPAIGN_ID + 2).stream().map(campaignId -> {
            service.reserve(campaignId, 1);
            return service.issue(campaignId, 1).couponId();
        }).toList();
        jdbc.update("""
            UPDATE owned_coupon SET issued_at =
              CASE WHEN id = ? THEN '2026-11-12 10:00:00' ELSE '2026-11-12 09:00:00' END
            """, couponIds.getFirst());
        assertThat(service.listIssued("alice", 0, 2)).extracting(OwnedCouponInfo::couponId)
            .containsExactly(couponIds.getFirst(), couponIds.getLast());
        assertThat(service.listIssued("alice", 1, 2)).extracting(OwnedCouponInfo::couponId)
            .containsExactly(couponIds.get(1));
        assertThat(service.listIssued("alice", 2, 2)).isEmpty();
        assertThat(service.listIssued("alice", Integer.MAX_VALUE, 2)).isEmpty();
    }

    private void assertDatabaseCounts(int waiting, int issued) {
        assertThat(jdbc.queryForObject("SELECT waiting_count FROM coupon_campaign WHERE id = ?",
            Integer.class, CAMPAIGN_ID)).isEqualTo(waiting);
        assertThat(jdbc.queryForObject("SELECT issued_count FROM coupon_campaign WHERE id = ?",
            Integer.class, CAMPAIGN_ID)).isEqualTo(issued);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM coupon_reservation WHERE status = 'WAITING'",
            Integer.class)).isEqualTo(waiting);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM coupon_reservation WHERE status = 'ISSUED'",
            Integer.class)).isEqualTo(issued);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM owned_coupon", Integer.class)).isEqualTo(issued);
    }

    private void flushChanges() {
        var entityManager = EntityManagerFactoryUtils.getTransactionalEntityManager(context.getBean(EntityManagerFactory.class));
        assertThat(entityManager).isNotNull();
        entityManager.flush();
    }

    private void insertDuplicateFixtureUser() {
        jdbc.update("""
            INSERT INTO `user` (id, point_balance, created_at, updated_at)
            VALUES (1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
            """);
    }

    private boolean causedByInsertTrigger(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && "45000".equals(sql.getSQLState())
                && sql.getMessage().contains("injected owned_coupon insert failure")) {
                return true;
            }
        }
        return false;
    }

    private List<Outcome> race(long firstUser, long secondUser, LongFunction<CouponReservationInfo> action) throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        Set<Long> connections = ConcurrentHashMap.newKeySet();
        var executor = Executors.newFixedThreadPool(2);
        List<Outcome> outcomes;
        try {
            var first = executor.submit(() -> runAtBarrier(firstUser, action, ready, start, connections));
            var second = executor.submit(() -> runAtBarrier(secondUser, action, ready, start, connections));
            assertThat(ready.await(10, TimeUnit.SECONDS)).as("two independent MySQL transactions ready").isTrue();
            assertThat(connections).hasSize(2);
            start.countDown();
            outcomes = List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(connections).doesNotContain(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
        return outcomes;
    }

    private Outcome runAtBarrier(long userId, LongFunction<CouponReservationInfo> action,
                                 CountDownLatch ready, CountDownLatch start, Set<Long> connections) {
        try {
            return transactions.execute(transaction -> {
                connections.add(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
                ready.countDown();
                try {
                    assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                var result = action.apply(userId);
                assertThat(result.userId()).isEqualTo(userId);
                assertThat(result.campaignId()).isEqualTo(CAMPAIGN_ID);
                return new Outcome(result, null);
            });
        } catch (CouponException exception) {
            return new Outcome(null, exception.reason());
        }
    }

    private record Outcome(CouponReservationInfo reservation, CouponException.Reason reason) { }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackages = {"com.loopers.infrastructure.coupon", "com.loopers.infrastructure.user"})
    @ComponentScan("com.loopers.infrastructure.coupon")
    @Import({CouponIssuanceService.class, UserRepositoryImpl.class, UserResolver.class, FixtureUserIdentityRepository.class})
    static class CouponDatabaseConfig {
        @Bean
        DataSource dataSource() {
            var dataSource = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
            new ResourceDatabasePopulator(
                new ClassPathResource("db/schema/002-user.sql"),
                new ClassPathResource("db/schema/013-coupon-issuance.sql")
            ).execute(dataSource);
            return dataSource;
        }

        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(dataSource);
            factory.setPackagesToScan("com.loopers.domain.coupon", "com.loopers.domain.user");
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of(
                "hibernate.hbm2ddl.auto", "validate",
                "hibernate.timezone.default_storage", "NORMALIZE_UTC",
                "hibernate.jdbc.time_zone", "UTC"
            ));
            return factory;
        }

        @Bean
        PlatformTransactionManager transactionManager(EntityManagerFactory entityManagerFactory) {
            return new JpaTransactionManager(entityManagerFactory);
        }
    }
}
