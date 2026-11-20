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
    @DisplayName("공통 입력 고정 후 두 JVM의 계산·재시작을 거쳐도 늦은 구매는 다음 명시적 revision에서만 공개한다")
    void publishesLateOrdersOnlyInTheNextCommonSnapshot() throws Exception {
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
        assertThat(lab.ranking(port, week).path("status").asText()).isEqualTo("NOT_PUBLISHED");
        try (var nodeA = processes.resident("node-a", 2, 12); var nodeB = processes.resident("node-b", 2, 12);
             var gate = database.connectAsAdministrator()) {
            processes.awaitMembers(2, nodeA, nodeB);
            assertThat(nodeA.process().pid()).isNotEqualTo(nodeB.process().pid());
            request(processes, week, 1);
            String previous = processes.awaitPublication(week, 1);
            assertRanks(lab.ranking(port, week), previous, 7, 2, 8, 1);

            long lower = lab.deferOrder(port);
            for (int order = 0; order < 3; order++) {
                awaitActivity(lab.jdbc, lab.confirmOrder(port, "[{\"productId\":7,\"quantity\":1}]"), 1);
            }
            lab.installCalculationGate(week);
            namedLock(gate, true);
            request(processes, week, 2);
            lab.awaitDelayedPartition();
            String current = cutoff(lab.jdbc, week, 2).id();
            long boundary = lab.jdbc.queryForObject("""
                SELECT order_max FROM weekly_ranking_order_partitions WHERE snapshot_id=? AND order_partition_id=0
                """, Long.class, current);
            assertThat(lower).isLessThan(boundary);
            await().atMost(Duration.ofSeconds(90)).untilAsserted(() -> assertThat(lab.jdbc.queryForObject("""
                SELECT state FROM weekly_ranking_order_partitions WHERE snapshot_id=? AND order_partition_id=0
                """, String.class, current)).isEqualTo("DONE"));
            var cutoff = cutoff(lab.jdbc, week, 2);
            assertPointer(lab.jdbc, week, 1, previous);
            assertRanks(lab.ranking(port, week), previous, 7, 2, 8, 1);
            assertThat(lab.jdbc.update("UPDATE order_event_outbox SET next_attempt_at=UTC_TIMESTAMP(6) WHERE order_id=?", lower)).isOne();
            awaitActivity(lab.jdbc, lower, 1);
            long upper = lab.confirmOrder(port, "[{\"productId\":8,\"quantity\":4}]");
            awaitActivity(lab.jdbc, upper, 1);
            assertThat(upper).isGreaterThanOrEqualTo(boundary);
            assertThat(lab.jdbc.queryForList("""
                SELECT CONCAT(order_id,':',product_id,':',quantity) FROM product_purchase_activities
                WHERE confirmed_at>=? AND confirmed_at<? AND order_id IN (?,?) ORDER BY order_id
                """, String.class, week, week.plusWeeks(1), lower, upper)).containsExactly(lower + ":8:3", upper + ":8:4");
            var beforeRelease = cutoff(lab.jdbc, week, 2);
            assertThat(beforeRelease).isEqualTo(cutoff);
            assertThat(lateInputs(lab.jdbc, cutoff.id(), lower, upper)).isEmpty();
            assertPointer(lab.jdbc, week, 1, previous);
            var pending = lab.ranking(port, week);
            assertRanks(pending, previous, 7, 2, 8, 1);
            System.out.printf("C07 waiting revision=2 nodes=%d,%d boundary=%d lateOrders=%d,%d cutoff=%s previousAPI=%s%n",
                nodeA.process().pid(), nodeB.process().pid(), boundary, lower, upper, cutoff, pending);
            namedLock(gate, false);

            String second = processes.awaitPublication(week, 2, nodeA, nodeB);
            assertThat(second).isEqualTo(cutoff.id());
            var secondAPI = lab.ranking(port, week);
            var included = lateInputs(lab.jdbc, second, lower, upper);
            var completed = cutoff(lab.jdbc, week, 2);
            var executions = lab.jdbc.queryForList("""
                SELECT b.JOB_INSTANCE_ID AS instance_id,b.JOB_EXECUTION_ID AS execution_id,b.STATUS AS job_status,
                       gen.PARAMETER_VALUE AS generation FROM BATCH_JOB_EXECUTION b
                JOIN BATCH_JOB_EXECUTION_PARAMS id ON id.JOB_EXECUTION_ID=b.JOB_EXECUTION_ID AND id.PARAMETER_NAME='snapshotId'
                JOIN BATCH_JOB_EXECUTION_PARAMS op ON op.JOB_EXECUTION_ID=b.JOB_EXECUTION_ID AND op.PARAMETER_NAME='orderPartitionId'
                JOIN BATCH_JOB_EXECUTION_PARAMS gen ON gen.JOB_EXECUTION_ID=b.JOB_EXECUTION_ID AND gen.PARAMETER_NAME='generation'
                WHERE id.PARAMETER_VALUE=? AND op.PARAMETER_VALUE='1' ORDER BY b.JOB_EXECUTION_ID
                """, second);
            var owners = lab.jdbc.queryForList("""
                SELECT owner_id FROM weekly_ranking_order_partitions WHERE snapshot_id=? AND state='DONE' ORDER BY order_partition_id
                """, String.class, second);
            System.out.printf("C07 published revision=2 snapshot=%s lateInputs=%s completed=%s executions=%s owners=%s API=%s%n",
                second, included, completed, executions, owners, secondAPI);
            assertThat(executions).hasSize(2);
            assertThat(executions).extracting(row -> row.get("job_status")).containsExactly("FAILED", "COMPLETED");
            assertThat(executions).extracting(row -> row.get("generation")).containsExactly("1", "2");
            assertThat(executions.getFirst().get("instance_id")).isEqualTo(executions.getLast().get("instance_id"));
            assertThat(executions.getFirst().get("execution_id")).isNotEqualTo(executions.getLast().get("execution_id"));
            assertThat(owners).containsExactly("node-a", "node-b");
            assertThat(included).as("late inputs after the second JVM restarted").isEmpty();
            assertThat(cutoff.state()).isEqualTo("FROZEN");
            assertThat(cutoff.contract()).isEqualTo(3);
            assertThat(cutoff.rows()).isEqualTo(5);
            assertThat(cutoff.hash()).hasSize(64);
            assertThat(cutoff.uniformFreeze()).isEqualTo(2);
            assertThat(cutoff.frozenRows()).isEqualTo(5);
            assertThat(completed).isEqualTo(cutoff);
            assertPointer(lab.jdbc, week, 2, second);
            assertRanks(secondAPI, second, 7, 5, 8, 1);

            namedLock(gate, true);
            request(processes, week, 3);
            lab.awaitDelayedPartition();
            var nextCutoff = cutoff(lab.jdbc, week, 3);
            var nextInputs = lateInputs(lab.jdbc, nextCutoff.id(), lower, upper);
            assertThat(nextInputs).containsExactly(lower + ":8:3", upper + ":8:4");
            assertThat(nextCutoff.uniformFreeze()).isEqualTo(2);
            assertThat(nextCutoff.rows()).isEqualTo(7);
            assertThat(nextCutoff.frozenRows()).isEqualTo(7);
            assertPointer(lab.jdbc, week, 2, second);
            var whilePublishing = lab.ranking(port, week);
            assertRanks(whilePublishing, second, 7, 5, 8, 1);
            System.out.printf("C07 waiting revision=3 cutoff=%s lateInputs=%s previousAPI=%s%n",
                nextCutoff, nextInputs, whilePublishing);
            namedLock(gate, false);
            String third = processes.awaitPublication(week, 3, nodeA, nodeB);
            assertThat(third).isEqualTo(nextCutoff.id()).isNotEqualTo(second);
            assertPointer(lab.jdbc, week, 3, third);
            assertThat(lateInputs(lab.jdbc, second, lower, upper)).isEmpty();
            assertThat(lateInputs(lab.jdbc, third, lower, upper)).isEqualTo(nextInputs);
            assertThat(cutoff(lab.jdbc, week, 3)).isEqualTo(nextCutoff);
            var published = lab.ranking(port, week);
            assertRanks(published, third, 8, 8, 7, 5);
            System.out.printf("C07 published revision=3 snapshot=%s lateInputs=%s API=%s%n", third, nextInputs, published);
        }
    }

    private static void request(RankingProcessLab processes, LocalDate week, int revision) throws Exception {
        try (var request = processes.request(week, revision, 2)) {
            request.awaitSuccess();
        }
    }

    private static void namedLock(Connection gate, boolean acquire) throws Exception {
        try (var statement = gate.createStatement(); var result = statement.executeQuery(acquire
            ? "SELECT GET_LOCK('ranking-flow-calculation',0)" : "SELECT RELEASE_LOCK('ranking-flow-calculation')")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getInt(1)).isOne();
        }
    }

    private static void assertPointer(JdbcTemplate jdbc, LocalDate week, int revision, String snapshot) {
        assertThat(jdbc.queryForObject("SELECT CONCAT(revision,':',snapshot_id) FROM weekly_ranking_publications WHERE period_start=?",
            String.class, week)).isEqualTo(revision + ":" + snapshot);
    }

    private static List<String> lateInputs(JdbcTemplate jdbc, String snapshot, long lower, long upper) {
        return jdbc.queryForList("""
            SELECT CONCAT(order_id,':',product_id,':',quantity) FROM weekly_ranking_snapshot_inputs
            WHERE snapshot_id=? AND order_id IN (?,?) ORDER BY order_id,product_id
            """, String.class, snapshot, lower, upper);
    }

    private static Cutoff cutoff(JdbcTemplate jdbc, LocalDate week, int revision) {
        return jdbc.queryForObject("""
            SELECT m.snapshot_id,m.state,m.input_contract_version,m.input_count,m.input_checksum,CAST(m.frozen_at AS CHAR),
                   (SELECT COUNT(*) FROM weekly_ranking_order_partitions o WHERE o.snapshot_id=m.snapshot_id
                      AND o.frozen_at=m.frozen_at AND o.input_count IS NOT NULL AND o.input_checksum IS NOT NULL),
                   (SELECT COUNT(*) FROM weekly_ranking_snapshot_inputs i WHERE i.snapshot_id=m.snapshot_id),
                   (SELECT GROUP_CONCAT(CONCAT(o.order_partition_id,':',o.input_count,':',o.input_checksum)
                       ORDER BY o.order_partition_id) FROM weekly_ranking_order_partitions o WHERE o.snapshot_id=m.snapshot_id)
            FROM weekly_ranking_snapshots m WHERE m.period_start=? AND m.revision=?
            """, (row, n) -> new Cutoff(row.getString(1), row.getString(2), row.getInt(3), row.getObject(4, Long.class),
            row.getString(5), row.getString(6), row.getInt(7), row.getInt(8), row.getString(9)), week, revision);
    }

    private record Cutoff(String id, String state, int contract, Long rows, String hash, String frozenAt,
                          int uniformFreeze, int frozenRows, String partitionHashes) {}

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
