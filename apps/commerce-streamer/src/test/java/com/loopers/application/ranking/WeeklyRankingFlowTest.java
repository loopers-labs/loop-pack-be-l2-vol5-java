package com.loopers.application.ranking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.loopers.application.ordering.facade.ConsumeOrderConfirmationFacade;
import com.loopers.application.ordering.service.RecordOrderEventFailureService;
import com.loopers.application.ordering.service.RecordPurchaseActivityService;
import com.loopers.application.ordering.service.VerifyProcessedOrderConfirmationService;
import com.loopers.config.jpa.JpaConfig;
import com.loopers.infrastructure.messaging.ordering.OrderConfirmedKafkaConfiguration;
import com.loopers.infrastructure.persistence.ordering.OrderEventFailureJpaRepository;
import com.loopers.infrastructure.persistence.ordering.PurchaseActivityJpaRepository;
import com.loopers.interfaces.consumer.ordering.OrderConfirmedKafkaConsumer;
import com.loopers.support.preservation.PreservationContexts;
import com.loopers.support.preservation.PreservedMySql;
import com.loopers.testcontainers.KafkaTestFixture;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

class WeeklyRankingFlowTest {
    @TempDir(cleanup = CleanupMode.ON_SUCCESS) private Path logs;

    @Test
    @DisplayName("실제 주문에서 나온 구매는 담당 주문 파티션의 고정 시점에 따라 포함되고 두 JVM이 만든 공개본을 API에서 조회한다")
    void publishesRealOrdersAcrossOrderPartitionFreezeTimes() throws Exception {
        try (var database = new PreservedMySql(); var kafka = KafkaTestFixture.create();
             var redis = new GenericContainer<>(DockerImageName.parse("redis:7.0")).withExposedPorts(6379)) {
            kafka.start();
            redis.start();
            String broker = kafka.getBootstrapServers();
            try (var lab = new WeeklyRankingFlowLab(logs, database, broker, redis); var pg = lab.startPg()) {
                lab.initializeTopic();
                try (var streamer = PreservationContexts.start(PurchaseConsumer.class, database.arguments(
                         "--spring.kafka.bootstrap-servers=" + broker, "--order-events.topic=" + WeeklyRankingFlowLab.TOPIC,
                         "--order-events.group=" + WeeklyRankingFlowLab.ACTIVITY_GROUP));
                     var api = lab.startApi("api", pg.port("TestPgApplication"))) {
                    var processes = new RankingProcessLab(logs, database);
                    exercise(lab, processes, api.port("CommerceApiApplication"), broker, database);
                }
            }
        }
    }

    private void exercise(WeeklyRankingFlowLab lab, RankingProcessLab processes, int port, String broker,
                          PreservedMySql database) throws Exception {
        long first = lab.confirmOrder(port, "[{\"productId\":7,\"quantity\":2},{\"productId\":8,\"quantity\":1}]");
        awaitActivity(lab.jdbc, first, 2);
        lab.eventAt(first, 0);
        replay(broker, lab.jdbc, first);
        awaitActivity(lab.jdbc, first, 2);
        assertThat(lab.jdbc.queryForObject("SELECT COUNT(*) FROM order_confirmation_inbox WHERE order_id=?", Long.class, first))
            .isEqualTo(1);
        LocalDate week = lab.jdbc.queryForObject("SELECT DATE(confirmed_at) FROM order_confirmation_inbox WHERE order_id=?",
            LocalDate.class, first).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        // Reserve an order in the lower order partition but postpone its Outbox delivery.
        lab.jdbc.execute("""
            CREATE TRIGGER defer_event BEFORE INSERT ON order_event_outbox FOR EACH ROW SET NEW.next_attempt_at='9998-01-01'
            """);
        long deferred;
        try {
            deferred = lab.confirmOrder(port, "[{\"productId\":8,\"quantity\":3}]");
        } finally {
            lab.jdbc.execute("DROP TRIGGER defer_event");
        }
        for (int order = 0; order < 3; order++) {
            awaitActivity(lab.jdbc, lab.confirmOrder(port, "[{\"productId\":7,\"quantity\":1}]"), 1);
        }
        assertThat(lab.ranking(port, week).path("status").asText()).isEqualTo("NOT_PUBLISHED");
        try (var gate = database.connectAsAdministrator(); var nodeA = processes.resident("node-a", 2, 12);
             var nodeB = processes.resident("node-b", 2, 12)) {
            processes.awaitMembers(2, nodeA, nodeB);
            try (var statement = gate.createStatement()) {
                statement.execute("SELECT GET_LOCK('ranking-flow-order-partition-one',0)");
            }
            installFreezeGate(lab.jdbc, week);
            try (var request = processes.request(week, 1, 2)) {
                request.awaitSuccess();
                await().atMost(Duration.ofSeconds(90)).untilAsserted(() -> assertThat(lab.jdbc.queryForList("""
                    SELECT s.order_max FROM weekly_ranking_order_partitions s JOIN weekly_ranking_plans p USING(snapshot_id)
                    WHERE p.period_start=? AND p.revision=1 AND s.order_partition_id=0 AND s.frozen_at IS NOT NULL
                    """, Long.class, week)).singleElement().satisfies(bound -> assertThat(bound).isGreaterThan(deferred)));
                assertThat(lab.ranking(port, week).path("status").asText()).isEqualTo("NOT_PUBLISHED");
                lab.jdbc.update("UPDATE order_event_outbox SET next_attempt_at=UTC_TIMESTAMP(6) WHERE order_id=?", deferred);
                awaitActivity(lab.jdbc, deferred, 1);
                long upperOrder = lab.confirmOrder(port, "[{\"productId\":8,\"quantity\":4}]");
                awaitActivity(lab.jdbc, upperOrder, 1);
                release(gate);
                String firstSnapshot = processes.awaitPublication(week, 1);
                assertThat(lab.jdbc.queryForList("""
                    SELECT DISTINCT order_id FROM weekly_ranking_snapshot_inputs WHERE snapshot_id=? ORDER BY order_id
                    """, Long.class, firstSnapshot)).contains(first, upperOrder).doesNotContain(deferred);
                assertRanks(lab.ranking(port, week), firstSnapshot, 7, 5, 8, 5);
                assertThat(lab.jdbc.queryForList("""
                    SELECT preferred_node FROM weekly_ranking_order_partitions WHERE snapshot_id=? AND state='DONE' ORDER BY order_partition_id
                    """, String.class, firstSnapshot)).containsExactly("node-a", "node-b");
                try (var revision = processes.request(week, 2, 2)) {
                    revision.awaitSuccess();
                }
                String secondSnapshot = processes.awaitPublication(week, 2);
                assertThat(secondSnapshot).isNotEqualTo(firstSnapshot);
                assertRanks(lab.ranking(port, week), secondSnapshot, 8, 8, 7, 5);
                assertThat(lab.jdbc.queryForObject("""
                    SELECT COUNT(*) FROM weekly_ranking_snapshot_inputs WHERE snapshot_id=? AND order_id=?
                    """, Long.class, firstSnapshot, deferred)).isZero();
            } finally {
                release(gate);
                lab.jdbc.execute("DROP TRIGGER gate_second_freeze");
            }
        }
    }

    private static void installFreezeGate(JdbcTemplate jdbc, LocalDate week) {
        // Block before Spring Batch starts the freeze step, outside the source-copy transaction.
        jdbc.execute("""
            CREATE TRIGGER gate_second_freeze BEFORE INSERT ON BATCH_STEP_EXECUTION FOR EACH ROW
            BEGIN
              IF NEW.STEP_NAME='freezeWeeklyRankingOrderPartition' AND EXISTS (
                SELECT 1 FROM BATCH_JOB_EXECUTION_PARAMS partition_param
                JOIN BATCH_JOB_EXECUTION_PARAMS id ON id.JOB_EXECUTION_ID=partition_param.JOB_EXECUTION_ID AND id.PARAMETER_NAME='snapshotId'
                JOIN weekly_ranking_plans p ON p.snapshot_id=id.PARAMETER_VALUE
                WHERE partition_param.JOB_EXECUTION_ID=NEW.JOB_EXECUTION_ID AND partition_param.PARAMETER_NAME='orderPartitionId' AND partition_param.PARAMETER_VALUE='1'
                  AND p.period_start='%s' AND p.revision=1
              ) THEN
                IF GET_LOCK('ranking-flow-order-partition-one',120) <> 1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='freeze gate timeout'; END IF;
                DO RELEASE_LOCK('ranking-flow-order-partition-one');
              END IF;
            END
            """.formatted(week));
    }

    private static void release(Connection gate) throws Exception {
        try (var statement = gate.createStatement()) {
            statement.execute("SELECT RELEASE_LOCK('ranking-flow-order-partition-one')");
        }
    }

    private static void awaitActivity(JdbcTemplate jdbc, long order, int items) {
        await().atMost(Duration.ofSeconds(40)).untilAsserted(() -> assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM product_purchase_activities WHERE order_id=?", Integer.class, order)).isEqualTo(items));
    }

    private static void assertRanks(JsonNode view, String snapshot, long first, long firstScore, long second, long secondScore) {
        assertThat(view.path("status").asText()).isEqualTo("PUBLISHED");
        assertThat(view.path("snapshotId").asText()).isEqualTo(snapshot);
        assertThat(view.path("items")).hasSize(2);
        for (var expected : List.of(new long[]{1, first, firstScore}, new long[]{2, second, secondScore})) {
            var item = view.path("items").get((int) expected[0] - 1);
            assertThat(List.of(item.path("rank").asLong(), item.path("productId").asLong(), item.path("score").asLong()))
                .containsExactly(expected[0], expected[1], expected[2]);
        }
    }

    private static void replay(String broker, JdbcTemplate jdbc, long order) throws Exception {
        var config = Map.<String, Object>of("bootstrap.servers", broker);
        try (var producer = new KafkaProducer<>(config, new StringSerializer(), new ByteArraySerializer());
             var admin = Admin.create(config)) {
            var bytes = jdbc.queryForObject("SELECT payload FROM order_event_outbox WHERE order_id=?", byte[].class, order);
            var ack = producer.send(new ProducerRecord<>(WeeklyRankingFlowLab.TOPIC, Long.toString(order), bytes))
                .get(15, TimeUnit.SECONDS);
            var partition = new TopicPartition(ack.topic(), ack.partition());
            await().atMost(Duration.ofSeconds(25)).untilAsserted(() -> {
                var committed = admin.listConsumerGroupOffsets(WeeklyRankingFlowLab.ACTIVITY_GROUP)
                    .partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS);
                assertThat(committed).containsKey(partition);
                assertThat(committed.get(partition).offset()).isGreaterThan(ack.offset());
            });
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @ComponentScan(basePackageClasses = JpaConfig.class)
    @Import({OrderConfirmedKafkaConfiguration.class, OrderConfirmedKafkaConsumer.class, ConsumeOrderConfirmationFacade.class,
        RecordPurchaseActivityService.class, VerifyProcessedOrderConfirmationService.class, PurchaseActivityJpaRepository.class,
        RecordOrderEventFailureService.class, OrderEventFailureJpaRepository.class})
    static class PurchaseConsumer {}
}
