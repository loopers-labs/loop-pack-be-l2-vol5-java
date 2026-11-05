package com.loopers.application.order;

import com.loopers.domain.order.OrderConfirmationPublisher;
import com.loopers.domain.order.OrderConfirmed;
import com.loopers.domain.order.OrderOutbox;
import com.loopers.domain.order.OrderOutboxRecorder;
import com.loopers.domain.order.OrderOutboxStatus;
import com.loopers.infrastructure.order.OrderOutboxJpaRepository;
import com.loopers.support.order.SpringAppProcess;
import com.loopers.testcontainers.KafkaTestBroker;
import com.loopers.testcontainers.MySqlTestContainersConfig;
import com.loopers.utils.DatabaseCleanUp;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.ConsumerGroupState;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kafka 흐름에서 생길 수 있는 장애 상황을 실제 구매 활동 앱(별도 JVM)까지 이어 관찰한다.
 * 몰림, 잘못된 메시지, consumer 강제 종료, broker 멈춤, 보낼 수 없는 메시지, DB 멈춤, 대량 재처리 실수, consumer 추가, relay 중복 실행,
 * 재시도 예산보다 긴 DB 장애를 본다.
 * <p>
 * 대량의 사건은 주문 API·PG를 거치지 않고 Outbox 기록기로 합성해 넣는다. relay 입장에서는 주문 확정이 남긴 행과 같다.
 * 잘못된 메시지는 테스트가 저장된 행을 SQL로 고친 테스트 전용 입력이며 정상 업무 데이터가 아니다.
 * relay는 제품 설정과 같은 "한 회차 최대 200건, 회차 뒤 10초 쉼"을 테스트 스레드가 반복한다.
 * 크기 초과를 만들려고 이 테스트에서만 producer의 max.request.size를 2,000바이트로 낮춘다. broker는 1대다.
 * 실행: {@code ./gradlew :apps:commerce-api:experimentTest --tests '*KafkaFailureScenariosExperimentTest'}
 */
@Tag("experiment")
@SpringBootTest(properties = {
    "commerce.order-events.enabled=true",
    "spring.kafka.producer.properties.max.request.size=2000"
})
class KafkaFailureScenariosExperimentTest {
    private static final KafkaTestBroker BROKER = new KafkaTestBroker();
    private static final String TOPIC = "order-events-failures-" + UUID.randomUUID();
    private static final String DEAD_LETTER_TOPIC = TOPIC + "-dlt";
    private static final String GROUP = "purchase-activity";
    private static final int PARTITIONS = 3;
    private static final Duration FIXED_DELAY = Duration.ofSeconds(10);
    private static final Duration RESULT_LIMIT = Duration.ofSeconds(240);
    private static SpringAppProcess streamer;

    @DynamicPropertySource
    static void endpoints(DynamicPropertyRegistry registry) {
        registry.add("commerce.order-events.topic", () -> TOPIC);
        registry.add("spring.kafka.bootstrap-servers", BROKER::bootstrapServers);
        registry.add("spring.kafka.admin.properties.bootstrap.servers", BROKER::bootstrapServers);
    }

    @Autowired private OrderOutboxRecorder recorder;
    @Autowired private OrderOutboxJpaRepository outbox;
    @Autowired private OrderOutboxRelay relay;
    @Autowired private OrderConfirmationPublisher publisher;
    @Autowired private OrderOutboxTransactions transactions;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DatabaseCleanUp cleanUp;

    @BeforeAll
    static void createTopics() throws Exception {
        try (AdminClient admin = admin()) {
            admin.createTopics(List.of(new NewTopic(TOPIC, PARTITIONS, (short) 1),
                new NewTopic(DEAD_LETTER_TOPIC, 1, (short) 1))).all().get(10, TimeUnit.SECONDS);
        }
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM product_purchase_activity");
        jdbc.update("DELETE FROM processed_order_confirmation");
        cleanUp.truncateAllTables();
    }

    @AfterAll
    static void closeResources() throws Exception {
        try {
            if (streamer != null) {
                streamer.close();
            }
        } finally {
            BROKER.close();
        }
    }

    /** S1. 600건이 한꺼번에 들어오면 relay와 consumer는 언제 모두 따라잡나? */
    @Test
    void burstOfOrdersConvergesOnce() throws Exception {
        startStreamer();
        long first = 1_000_000L;
        int count = 600;
        long started = System.nanoTime();
        record(first, count);

        long publishedMillis = runRelayUntil(() -> pending(first, count) == 0);
        long convergedMillis = awaitMillis(started, () -> activities(first, count) == count);

        Map<String, Object> report = report("S1_burst", first, count, started);
        report.put("allPublishedAfterInsertMillis", publishedMillis);
        report.put("allActivitiesAfterInsertMillis", convergedMillis);
        print(report);

        assertThat(activities(first, count)).isEqualTo(count);
        assertThat(completions(first, count)).isEqualTo(count);
        assertThat(records(first, count)).isEqualTo(count);
    }

    /** S2. 정상 사건 사이에 잘못된 메시지 세 건이 섞이면? */
    @Test
    void badMessagesAreIsolatedWhileTheRestLand() throws Exception {
        startStreamer();
        long first = 2_000_000L;
        int count = 100;
        long started = System.nanoTime();
        long deadLettersBefore = deadLetters();
        record(first, count);

        long broken = first + 30;
        long unsupported = first + 50;
        long conflicting = first + 70;
        corruptPayload(broken, "{\"eventType\":\"OrderConfirmed\",\"schemaVersion\":1,");
        corruptPayload(unsupported, payloadOf(unsupported).replace("\"schemaVersion\":1", "\"schemaVersion\":2"));
        // 이미 처리될 주문(first + 10)과 같은 orderId를 다른 eventId·다른 수량으로 보낸다.
        jdbc.update("UPDATE order_outbox SET message_key = ?, payload = ? WHERE order_id = ?",
            String.valueOf(first + 10), payloadOf(conflicting)
                .replace("\"orderId\":" + conflicting, "\"orderId\":" + (first + 10))
                .replace("\"quantity\":1", "\"quantity\":9"), conflicting);

        runRelayUntil(() -> pending(first, count) == 0);
        awaitMillis(started, () -> deadLetters() - deadLettersBefore == 3 && activities(first, count) == count - 3);

        Map<String, Object> report = report("S2_bad_messages", first, count, started);
        report.put("deadLetters", deadLetters() - deadLettersBefore);
        report.put("activityOfFirstPlus10", activityQuantity(first + 10));
        print(report);

        assertThat(deadLetters() - deadLettersBefore).isEqualTo(3);
        assertThat(activities(first, count)).isEqualTo(count - 3);
        assertThat(activityQuantity(first + 10)).as("충돌 메시지가 기존 구매 활동을 바꾸지 않는다").isEqualTo(1);
        assertThat(pending(first, count)).isZero();
    }

    /** S3. consumer가 강제 종료된 동안 300건이 발행되면? */
    @Test
    void consumerKilledDuringBurstCatchesUpOnceAfterRestart() throws Exception {
        startStreamer();
        streamer.kill();
        long first = 3_000_000L;
        int count = 300;
        long started = System.nanoTime();
        record(first, count);

        runRelayUntil(() -> pending(first, count) == 0);
        int activitiesWhileDown = activities(first, count);
        long restarting = System.nanoTime();
        streamer = null;
        startStreamer();
        long caughtUpMillis = awaitMillis(restarting, () -> activities(first, count) == count);

        Map<String, Object> report = report("S3_consumer_killed", first, count, started);
        report.put("activitiesWhileConsumerDown", activitiesWhileDown);
        report.put("publishedWhileConsumerDown", published(first, count));
        report.put("caughtUpAfterRestartMillis", caughtUpMillis);
        print(report);

        assertThat(activitiesWhileDown).isZero();
        assertThat(activities(first, count)).isEqualTo(count);
        assertThat(completions(first, count)).isEqualTo(count);
    }

    /** S4. broker가 20초 멈춘 동안 주문이 계속 들어오면? */
    @Test
    void brokerPauseDuringTrafficDrainsWithoutDuplicates() throws Exception {
        startStreamer();
        long first = 4_000_000L;
        int count = 200;
        long started = System.nanoTime();
        record(first, 50);
        runRelayUntil(() -> pending(first, 50) == 0);

        BROKER.pause();
        long pausedAt = System.nanoTime();
        ExecutorService resume = Executors.newSingleThreadExecutor();
        Future<?> resumed = resume.submit(() -> {
            sleep(Duration.ofSeconds(20));
            BROKER.resume();
        });
        long pendingPeak;
        try {
            record(first + 50, 150); // broker가 멈춘 동안 들어온 주문
            pendingPeak = pending(first, count);
            runRelayUntil(() -> pending(first, count) == 0);
            resumed.get(60, TimeUnit.SECONDS);
        } finally {
            resume.shutdownNow();
        }
        long convergedMillis = awaitMillis(pausedAt, () -> activities(first, count) == count);

        Map<String, Object> report = report("S4_broker_pause", first, count, started);
        report.put("pendingWhilePaused", pendingPeak);
        report.put("allActivitiesAfterPauseMillis", convergedMillis);
        print(report);

        assertThat(activities(first, count)).isEqualTo(count);
        assertThat(completions(first, count)).isEqualTo(count);
    }

    /** S5. 영원히 보낼 수 없는 메시지 하나가 있으면 뒤의 사건이 막히나? */
    @Test
    void permanentlyUnsendableRowDoesNotBlockOthers() throws Exception {
        startStreamer();
        long first = 5_000_000L;
        int count = 50;
        long started = System.nanoTime();
        record(first, count);
        long tooLarge = first + 5; // 앞쪽에 둬서 회차마다 먼저 걸리게 한다
        corruptPayload(tooLarge, payloadOf(tooLarge).replace("\"items\"", "\"padding\":\"" + "x".repeat(3_000) + "\",\"items\""));

        runRelayUntil(() -> published(first, count) == count - 1);
        long othersPublishedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();
        awaitMillis(started, () -> activities(first, count) == count - 1);
        OrderOutbox stuck = outbox.findAll().stream().filter(row -> row.getOrderId() == tooLarge).findFirst().orElseThrow();

        Map<String, Object> report = report("S5_unsendable_row", first, count, started);
        report.put("othersPublishedMillis", othersPublishedMillis);
        report.put("stuckStatus", stuck.getStatus());
        report.put("stuckAttempts", stuck.getAttemptCount());
        report.put("stuckLastError", stuck.getLastError());
        print(report);

        assertThat(activities(first, count)).isEqualTo(count - 1);
        assertThat(stuck.getStatus()).isEqualTo(OrderOutboxStatus.PENDING);
        assertThat(stuck.getLastError()).contains("RecordTooLarge");
    }

    /** S6. 주문이 흐르는 중에 DB가 20초 멈추면? relay와 consumer가 같은 MySQL을 쓴다. */
    @Test
    void databasePauseDuringTrafficConverges() throws Exception {
        startStreamer();
        long first = 6_000_000L;
        int count = 200;
        long started = System.nanoTime();
        record(first, count);

        RelayLoop loop = startRelayLoop(relay, () -> pending(first, count) == 0);
        sleep(Duration.ofSeconds(2)); // 첫 회차가 보내는 도중에 멈춘다
        MySqlTestContainersConfig.pause();
        long pausedAt = System.nanoTime();
        try {
            sleep(Duration.ofSeconds(20));
        } finally {
            MySqlTestContainersConfig.resume();
        }
        int relayErrors = finish(loop);
        long convergedMillis = awaitMillis(pausedAt, () -> activities(first, count) == count);
        awaitMillis(pausedAt, () -> lag() == 0);

        Map<String, Object> report = report("S6_database_pause", first, count, started);
        report.put("relayRoundsFailed", relayErrors);
        report.put("maxAttempts", maxAttempts(first, count));
        report.put("allActivitiesAfterPauseMillis", convergedMillis);
        print(report);

        assertThat(pending(first, count)).isZero();
        assertThat(activities(first, count)).isEqualTo(count);
        assertThat(completions(first, count)).isEqualTo(count);
    }

    /** S7. 운영자가 한 건이 아니라 전체를 재처리해 버리면? 운영 절차의 UPDATE를 범위로 실행한다. */
    @Test
    void mistakenMassReplayDoesNotDoublePurchases() throws Exception {
        startStreamer();
        long first = 7_000_000L;
        int count = 200;
        long started = System.nanoTime();
        record(first, count);
        runRelayUntil(() -> pending(first, count) == 0);
        awaitMillis(started, () -> activities(first, count) == count && lag() == 0);
        long recordsBefore = records(first, count);

        int replayed = jdbc.update("UPDATE order_outbox SET status = 'PENDING', published_at = NULL "
            + "WHERE status = 'PUBLISHED' AND order_id BETWEEN ? AND ?", first, first + count - 1);
        runRelayUntil(() -> pending(first, count) == 0);
        awaitMillis(started, () -> lag() == 0);

        Map<String, Object> report = report("S7_mass_replay", first, count, started);
        report.put("replayedRows", replayed);
        report.put("kafkaRecordsBeforeReplay", recordsBefore);
        print(report);

        assertThat(records(first, count)).isEqualTo(2L * count);
        assertThat(activities(first, count)).isEqualTo(count);
        assertThat(completions(first, count)).isEqualTo(count);
    }

    /** S8. 몰리는 중에 consumer를 하나 더 띄우면? partition을 다시 나누는 동안 빠지거나 겹치지 않을까? */
    @Test
    void addingConsumerDuringBurstRebalancesWithoutLoss() throws Exception {
        startStreamer();
        long first = 8_000_000L;
        int count = 600;
        long started = System.nanoTime();
        record(first, count);

        RelayLoop loop = startRelayLoop(relay, () -> pending(first, count) == 0);
        sleep(Duration.ofSeconds(3));
        SpringAppProcess second = SpringAppProcess.streamer(
            SpringAppProcess.Database.fromSystemProperties(), BROKER.bootstrapServers(), TOPIC);
        Map<String, Object> report;
        try {
            finish(loop);
            long convergedMillis = awaitMillis(started, () -> activities(first, count) == count && lag() == 0);
            report = report("S8_scale_out", first, count, started);
            report.put("allActivitiesMillis", convergedMillis);
            report.put("groupMembersAtEnd", groupMembers());
            report.put("secondConsumerAlive", second.isAlive());
        } finally {
            second.close();
        }
        print(report);

        assertThat(activities(first, count)).isEqualTo(count);
        assertThat(completions(first, count)).isEqualTo(count);
    }

    /** S9. 설정 실수로 relay가 두 곳에서 동시에 돌면? 프로세스 안의 잠금은 다른 relay를 막지 못한다. */
    @Test
    void twoRelaysByMistakeDuplicateRecordsButNotPurchases() throws Exception {
        startStreamer();
        long first = 9_000_000L;
        int count = 300;
        long started = System.nanoTime();
        record(first, count);

        var otherInstance = new OrderOutboxRelay(publisher, transactions, 200, Duration.ofSeconds(2), Duration.ofSeconds(60));
        RelayLoop a = startRelayLoop(relay, () -> pending(first, count) == 0);
        RelayLoop b = startRelayLoop(otherInstance, () -> pending(first, count) == 0);
        finish(a);
        finish(b);
        awaitMillis(started, () -> activities(first, count) == count && lag() == 0);

        Map<String, Object> report = report("S9_two_relays", first, count, started);
        report.put("duplicateRecords", records(first, count) - count);
        report.put("maxAttempts", maxAttempts(first, count));
        print(report);

        assertThat(activities(first, count)).isEqualTo(count);
        assertThat(completions(first, count)).isEqualTo(count);
    }

    /**
     * S10. 구매 활동 DB가 consumer의 재시도 예산보다 오래 멈추면? 두 정책을 비교한다.
     * A는 현재 방식(예산을 다 쓰면 listener를 멈춤), B는 같은 offset에서 계속 재시도하는 방식이다.
     * 5분을 기다리지 않으려고 이 실험에서만 A의 예산을 3초(기다린 간격의 합)로 줄였다. B는 1시간으로 둔다.
     * DB가 멈춘 동안 relay는 Outbox를 읽지 못하므로, 이미 발행된 메시지가 도착한 상황을 만들려고
     * 테스트가 Outbox에 기록해 둔 payload를 직접 broker로 보낸다.
     */
    @Test
    void databaseOutageLongerThanConsumerRetryBudget() throws Exception {
        Map<String, Object> stopping = consumerDatabaseOutage("A_stop_after_budget", 10_000_000L, "3s");
        Map<String, Object> retrying = consumerDatabaseOutage("B_keep_retrying", 11_000_000L, "1h");
        print(Map.<String, Object>of("scenario", "S10_database_outage_longer_than_retry_budget", "A", stopping, "B", retrying));

        assertThat((boolean) stopping.get("listenerStopped")).isTrue();
        assertThat((int) stopping.get("activitiesWithoutRestart")).isLessThan(50);
        assertThat((int) stopping.get("activitiesAfterRestart")).isEqualTo(50);
        assertThat((int) retrying.get("activitiesWithoutRestart")).as("계속 재시도하면 DB가 돌아온 뒤 스스로 이어 간다").isEqualTo(50);
    }

    private Map<String, Object> consumerDatabaseOutage(String policy, long first, String retryBudget) throws Exception {
        int count = 50;
        List<String> arguments = List.of("--commerce.order-events.consumer.retry.max-elapsed-time=" + retryBudget);
        if (streamer != null) {
            streamer.close();
        }
        streamer = SpringAppProcess.streamer(SpringAppProcess.Database.fromSystemProperties(), BROKER.bootstrapServers(),
            TOPIC, arguments);
        awaitMillis(System.nanoTime(), this::consumerAssigned);
        record(first, count);
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT message_key, payload FROM order_outbox WHERE order_id BETWEEN ? AND ? ORDER BY id", first, first + count - 1);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("policy", policy);
        result.put("retryBudget", retryBudget);
        MySqlTestContainersConfig.pause();
        try (var producer = new KafkaProducer<String, String>(Map.of(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BROKER.bootstrapServers(),
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class))) {
            for (Map<String, Object> row : rows) {
                producer.send(new ProducerRecord<>(TOPIC, (String) row.get("message_key"), (String) row.get("payload"))).get();
            }
            sleep(Duration.ofSeconds(40));
        } finally {
            MySqlTestContainersConfig.resume();
        }

        // DB가 돌아온 뒤 사람 손 없이 이어 가는지 본다
        long resumedAt = System.nanoTime();
        long deadline = resumedAt + Duration.ofSeconds(90).toNanos();
        while (System.nanoTime() < deadline && activitiesQuietly(first, count) < count) {
            Thread.sleep(500);
        }
        result.put("activitiesWithoutRestart", activitiesQuietly(first, count));
        result.put("lagWithoutRestart", lag());
        result.put("listenerStopped", streamer.logContains("processing failed after retries"));

        if ((int) result.get("activitiesWithoutRestart") < count) {
            long restarting = System.nanoTime();
            streamer.close();
            streamer = SpringAppProcess.streamer(SpringAppProcess.Database.fromSystemProperties(), BROKER.bootstrapServers(),
                TOPIC, arguments);
            result.put("caughtUpAfterRestartMillis", awaitMillis(restarting, () -> activities(first, count) == count));
        }
        result.put("activitiesAfterRestart", activities(first, count));
        result.put("completions", completions(first, count));
        streamer.close();
        streamer = null; // 다른 시나리오는 기본 설정의 consumer로 다시 시작한다
        return result;
    }

    private int activitiesQuietly(long first, int count) {
        try {
            return activities(first, count);
        } catch (RuntimeException databaseNotReady) {
            return -1;
        }
    }

    // ---- relay·consumer 실행 ----

    private record RelayLoop(ExecutorService worker, AtomicBoolean stop, Future<?> loop, AtomicInteger failedRounds) {
    }

    /**
     * 제품 스케줄러처럼 "회차 실행 → 10초 쉼"을 조건이 될 때까지 반복한다.
     * 회차가 예외로 끝나면 스케줄러처럼 기록만 하고 다음 회차로 넘어간다.
     */
    private RelayLoop startRelayLoop(OrderOutboxRelay target, Callable<Boolean> done) {
        var stop = new AtomicBoolean(false);
        var failedRounds = new AtomicInteger();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        Future<?> loop = worker.submit(() -> {
            while (!stop.get()) {
                try {
                    target.runOnce();
                    if (done.call()) {
                        return null;
                    }
                } catch (Exception roundFailed) {
                    failedRounds.incrementAndGet();
                }
                Thread.sleep(FIXED_DELAY.toMillis());
            }
            return null;
        });
        return new RelayLoop(worker, stop, loop, failedRounds);
    }

    /** relay 반복이 끝나기를 기다린다. 실패해도 스레드를 멈춘 뒤 돌아간다. 실패한 회차 수를 돌려준다. */
    private int finish(RelayLoop relayLoop) throws Exception {
        try {
            relayLoop.loop().get(RESULT_LIMIT.toMillis(), TimeUnit.MILLISECONDS);
        } finally {
            relayLoop.stop().set(true);
            relayLoop.worker().shutdownNow();
            assertThat(relayLoop.worker().awaitTermination(150, TimeUnit.SECONDS)).as("relay 종료").isTrue();
        }
        return relayLoop.failedRounds().get();
    }

    private long runRelayUntil(Callable<Boolean> done) throws Exception {
        long started = System.nanoTime();
        finish(startRelayLoop(relay, done));
        return Duration.ofNanos(System.nanoTime() - started).toMillis();
    }

    private long awaitMillis(long since, Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + RESULT_LIMIT.toNanos();
        while (System.nanoTime() < deadline) {
            assertThat(streamer.isAlive()).as("streamer 종료:\n%s", streamer.logTail()).isTrue();
            try {
                if (condition.call()) {
                    return Duration.ofNanos(System.nanoTime() - since).toMillis();
                }
            } catch (Exception notYet) {
                // DB·broker가 막 돌아온 직후의 조회 실패는 다시 확인한다
            }
            Thread.sleep(200);
        }
        throw new AssertionError("기한 초과\n" + streamer.logTail());
    }

    private void startStreamer() throws Exception {
        if (streamer != null && streamer.isAlive()) {
            return;
        }
        streamer = SpringAppProcess.streamer(SpringAppProcess.Database.fromSystemProperties(), BROKER.bootstrapServers(), TOPIC);
        long deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
        while (!consumerAssigned()) {
            assertThat(System.nanoTime()).as("consumer partition 배정\n%s", streamer.logTail()).isLessThan(deadline);
            Thread.sleep(500);
        }
    }

    private boolean consumerAssigned() throws Exception {
        try (AdminClient admin = admin()) {
            var group = admin.describeConsumerGroups(List.of(GROUP)).all().get(10, TimeUnit.SECONDS).get(GROUP);
            int assigned = group.members().stream().mapToInt(member -> member.assignment().topicPartitions().size()).sum();
            return group.state() == ConsumerGroupState.STABLE && assigned == PARTITIONS;
        } catch (Exception notReady) {
            return false;
        }
    }

    // ---- 입력 ----

    private void record(long firstOrderId, int count) {
        for (int i = 0; i < count; i++) {
            recorder.record(new OrderConfirmed(firstOrderId + i, Instant.now().truncatedTo(ChronoUnit.MICROS),
                List.of(new OrderConfirmed.Item(7L, 1))));
        }
    }

    private String payloadOf(long orderId) {
        return jdbc.queryForObject("SELECT payload FROM order_outbox WHERE order_id = ?", String.class, orderId);
    }

    private void corruptPayload(long orderId, String payload) {
        jdbc.update("UPDATE order_outbox SET payload = ? WHERE order_id = ?", payload, orderId);
    }

    // ---- 관찰 ----

    private Map<String, Object> report(String scenario, long first, int count, long started) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("scenario", scenario);
        report.put("events", count);
        report.put("published", published(first, count));
        report.put("pending", pending(first, count));
        report.put("kafkaRecords", records(first, count));
        report.put("completions", completions(first, count));
        report.put("activities", activities(first, count));
        report.put("endToEndP95Millis", endToEnd(first, count, 0.95));
        report.put("endToEndMaxMillis", endToEnd(first, count, 1.0));
        report.put("totalMillis", Duration.ofNanos(System.nanoTime() - started).toMillis());
        return report;
    }

    private void print(Map<String, Object> report) {
        System.out.println("KAFKA_FAILURE_SCENARIO " + report);
    }

    private int countByStatus(long first, int count, OrderOutboxStatus status) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM order_outbox WHERE status = ? AND order_id BETWEEN ? AND ?",
            Integer.class, status.name(), first, first + count - 1);
    }

    private int pending(long first, int count) {
        return countByStatus(first, count, OrderOutboxStatus.PENDING);
    }

    private int published(long first, int count) {
        return countByStatus(first, count, OrderOutboxStatus.PUBLISHED);
    }

    private int activities(long first, int count) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM product_purchase_activity WHERE order_id BETWEEN ? AND ?",
            Integer.class, first, first + count - 1);
    }

    private int completions(long first, int count) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM processed_order_confirmation WHERE order_id BETWEEN ? AND ?",
            Integer.class, first, first + count - 1);
    }

    private Integer activityQuantity(long orderId) {
        return jdbc.queryForObject("SELECT quantity FROM product_purchase_activity WHERE order_id = ?", Integer.class, orderId);
    }

    private int maxAttempts(long first, int count) {
        return jdbc.queryForObject("SELECT MAX(attempt_count) FROM order_outbox WHERE order_id BETWEEN ? AND ?",
            Integer.class, first, first + count - 1);
    }

    /** consumer group이 아직 읽지 않은 record 수(모든 partition의 끝 위치 − 커밋 위치). */
    private long lag() throws Exception {
        List<TopicPartition> partitions = IntStream.range(0, PARTITIONS).mapToObj(p -> new TopicPartition(TOPIC, p)).toList();
        try (AdminClient admin = admin(); var consumer = consumer()) {
            Map<TopicPartition, OffsetAndMetadata> committed = admin.listConsumerGroupOffsets(GROUP)
                .partitionsToOffsetAndMetadata().get(10, TimeUnit.SECONDS);
            Map<TopicPartition, Long> end = consumer.endOffsets(partitions, Duration.ofSeconds(10));
            return partitions.stream().mapToLong(p -> {
                OffsetAndMetadata offset = committed.get(p);
                return end.get(p) - (offset == null ? 0 : offset.offset());
            }).sum();
        }
    }

    private int groupMembers() throws Exception {
        try (AdminClient admin = admin()) {
            return admin.describeConsumerGroups(List.of(GROUP)).all().get(10, TimeUnit.SECONDS).get(GROUP).members().size();
        }
    }

    /** Outbox 기록부터 구매 활동 저장까지 걸린 시간의 분위수. 두 앱이 같은 기계의 시계로 기록한다. */
    private long endToEnd(long first, int count, double ratio) {
        List<Long> micros = jdbc.queryForList("""
            SELECT TIMESTAMPDIFF(MICROSECOND, o.created_at, a.created_at)
            FROM order_outbox o JOIN product_purchase_activity a ON a.order_id = o.order_id
            WHERE o.order_id BETWEEN ? AND ? ORDER BY 1""", Long.class, first, first + count - 1);
        if (micros.isEmpty()) {
            return 0;
        }
        return micros.get(Math.max(0, (int) Math.ceil(ratio * micros.size()) - 1)) / 1_000;
    }

    /** 이 시나리오의 orderId를 key로 가진 Kafka record 수. */
    private long records(long first, int count) {
        List<TopicPartition> partitions = IntStream.range(0, PARTITIONS).mapToObj(p -> new TopicPartition(TOPIC, p)).toList();
        try (var consumer = consumer()) {
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            Map<TopicPartition, Long> end = consumer.endOffsets(partitions, Duration.ofSeconds(10));
            long found = 0;
            while (partitions.stream().anyMatch(p -> consumer.position(p) < end.get(p))) {
                for (var record : consumer.poll(Duration.ofMillis(300))) {
                    long key = Long.parseLong(record.key());
                    if (key >= first && key < first + count) {
                        found++;
                    }
                }
            }
            return found;
        }
    }

    private long deadLetters() {
        var partition = new TopicPartition(DEAD_LETTER_TOPIC, 0);
        try (var consumer = consumer()) {
            return consumer.endOffsets(List.of(partition), Duration.ofSeconds(10)).get(partition);
        }
    }

    private static KafkaConsumer<String, String> consumer() {
        return new KafkaConsumer<>(Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BROKER.bootstrapServers(),
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false));
    }

    private static AdminClient admin() {
        return AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, BROKER.bootstrapServers()));
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
