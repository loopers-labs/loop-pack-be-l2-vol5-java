package com.loopers.application.order;

import com.loopers.domain.order.OrderConfirmationPublisher;
import com.loopers.domain.order.OrderConfirmed;
import com.loopers.domain.order.OrderOutboxDelivery;
import com.loopers.domain.order.OrderOutboxRecorder;
import com.loopers.testcontainers.KafkaTestBroker;
import com.loopers.utils.DatabaseCleanUp;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실패 처리까지 갖춘 후보 relay({@link BatchedOutboxRelay})를 지금 relay와 같은 상황에서 비교한다.
 * <p>
 * 평소 처리량, 보낼 수 없는 행이 섞일 때, 보낸 뒤 기록하기 전에 멈출 때, broker가 잠깐 멈출 때를 본다.
 * 제품 relay는 바꾸지 않는다. 크기 초과 행을 만들려고 이 테스트에서만 producer의 max.request.size를 2,000바이트로 낮춘다.
 * 운영과 같게 SQL 콘솔 로그는 끈다. broker 1대, topic partition 3.
 * 실행: {@code ./gradlew :apps:commerce-api:experimentTest --tests '*OutboxBatchedRelayExperimentTest'}
 */
@Tag("experiment")
@SpringBootTest(properties = {"commerce.order-events.enabled=true", "spring.jpa.show-sql=false",
    "spring.kafka.producer.properties.max.request.size=2000"})
class OutboxBatchedRelayExperimentTest {
    private static final KafkaTestBroker BROKER = new KafkaTestBroker();
    private static final String TOPIC = "order-outbox-batched-" + UUID.randomUUID();
    private static final int PARTITIONS = 3;
    private static final int BATCH_SIZE = 500;
    private static final Duration SEND_WAIT = Duration.ofSeconds(130);
    private static final Duration FIRST_RETRY = Duration.ofSeconds(2);
    private static final Duration MAX_RETRY = Duration.ofSeconds(60);
    private static final Duration LIMIT = Duration.ofSeconds(300);

    @DynamicPropertySource
    static void endpoints(DynamicPropertyRegistry registry) {
        registry.add("commerce.order-events.topic", () -> TOPIC);
        registry.add("spring.kafka.bootstrap-servers", BROKER::bootstrapServers);
    }

    enum Candidate {
        /** 지금 제품 relay. */
        SERIAL,
        /** 시도 기록·PUBLISHED를 묶고 보내기를 모으는 후보. 실패한 행은 행마다 재시도로 남긴다. */
        BATCHED
    }

    @Autowired private OrderOutboxRecorder recorder;
    @Autowired private OrderOutboxTransactions transactions;
    @Autowired private OrderConfirmationPublisher publisher;
    @Autowired private KafkaTemplate<Object, Object> kafka;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private NamedParameterJdbcTemplate namedJdbc;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private DatabaseCleanUp cleanUp;

    private long nextOrderId = 4_000_000L;

    @BeforeAll
    static void createTopic() throws Exception {
        try (AdminClient admin = admin()) {
            admin.createTopics(List.of(new NewTopic(TOPIC, PARTITIONS, (short) 1))).all().get(10, TimeUnit.SECONDS);
        }
    }

    @AfterEach
    void tearDown() {
        cleanUp.truncateAllTables();
    }

    @AfterAll
    static void closeBroker() {
        BROKER.close();
    }

    /** 평소: PENDING 3,000건을 쉬지 않고 비우는 속도. 워밍업 1번 뒤 3번 잰다. */
    @ParameterizedTest(name = "평소 {0}")
    @EnumSource(Candidate.class)
    void normalDrain(Candidate candidate) throws Exception {
        insert(300);
        drainUntil(relay(candidate, () -> { }), () -> count("status = 'PENDING'") == 0);
        cleanUp.truncateAllTables();

        List<Long> perSecond = new ArrayList<>();
        for (int repeat = 0; repeat < 3; repeat++) {
            insert(3_000);
            long recordsBefore = endOffsetSum();
            long millis = drainUntil(relay(candidate, () -> { }), () -> count("status = 'PENDING'") == 0);
            perSecond.add(Math.round(3_000 * 1_000.0 / millis));
            assertThat(count("status = 'PUBLISHED' AND attempt_count = 1")).isEqualTo(3_000);
            assertThat(endOffsetSum() - recordsBefore).as("Kafka 기록 수").isEqualTo(3_000);
            cleanUp.truncateAllTables();
        }
        report("normal", candidate, Map.of("perSecondRuns", perSecond,
            "perSecondAvg", Math.round(perSecond.stream().mapToLong(Long::longValue).average().orElse(0))));
    }

    /** 3,000건 중 30건을 보낼 수 없게(크기 초과) 만든다. 나머지가 다 나갈 때까지의 시간과 중복·오류 기록을 본다. */
    @ParameterizedTest(name = "보낼 수 없는 행 섞임 {0}")
    @EnumSource(Candidate.class)
    void permanentFailuresMixedIn(Candidate candidate) throws Exception {
        insert(3_000);
        int broken = jdbc.update("UPDATE order_outbox SET payload = CONCAT(payload, REPEAT(' ', 3000)) WHERE MOD(id, 100) = 0");
        long good = 3_000 - broken;
        long recordsBefore = endOffsetSum();

        long millis = drainUntil(relay(candidate, () -> { }), () -> count("status = 'PUBLISHED'") == good);

        assertThat(count("status = 'PENDING'")).as("막힌 행만 PENDING").isEqualTo(broken);
        assertThat(count("status = 'PENDING' AND last_error LIKE '%RecordTooLargeException%' AND last_error LIKE '%eventId=%'"))
            .as("막힌 행에 원인이 남음").isEqualTo(broken);
        assertThat(endOffsetSum() - recordsBefore).as("정상 행만 한 번씩 기록").isEqualTo(good);
        report("permanentFailures", candidate, Map.of("brokenRows", broken, "goodRows", good,
            "millisUntilGoodRowsPublished", millis,
            "brokenRowAttemptsMax", jdbc.queryForObject("SELECT MAX(attempt_count) FROM order_outbox WHERE status = 'PENDING'",
                Integer.class)));
    }

    /** 첫 회차에서 broker에 보낸 뒤 PUBLISHED를 적기 전에 relay가 멈춘다. 다시 돌렸을 때 다시 나가는 건수를 센다. */
    @ParameterizedTest(name = "기록 전 멈춤 {0}")
    @EnumSource(Candidate.class)
    void stopAfterSendBeforeRecording(Candidate candidate) throws Exception {
        insert(1_000);
        long recordsBefore = endOffsetSum();

        stopOnceAfterSend(candidate);
        drainUntil(relay(candidate, () -> { }), () -> count("status = 'PENDING'") == 0);

        long duplicates = endOffsetSum() - recordsBefore - 1_000;
        assertThat(count("status = 'PUBLISHED'")).isEqualTo(1_000);
        report("stopBeforeRecording", candidate, Map.of("rows", 1_000, "duplicateRecords", duplicates));
    }

    /**
     * broker를 10초 멈춘 상태에서 3,000건 비우기를 시작한다. 다 나갈 때까지의 시간과 중복을 본다.
     * 후보는 3,000건을 1초 안에 비우므로 비우는 도중에 멈추면 멈추기 전에 끝난다. 그래서 멈춘 뒤 시작한다.
     * producer가 broker 정보를 갖고 있도록 먼저 몇 건을 보낸다. 정보가 없으면 send()가 max.block.ms(5초)씩 막혀 다른 상황이 된다.
     */
    @ParameterizedTest(name = "broker 10초 멈춤 {0}")
    @EnumSource(Candidate.class)
    void brokerPausedWhileDraining(Candidate candidate) throws Exception {
        insert(10);
        drainUntil(relay(candidate, () -> { }), () -> count("status = 'PENDING'") == 0);
        cleanUp.truncateAllTables();
        insert(3_000);
        long recordsBefore = endOffsetSum();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        long millis;
        BROKER.pause();
        try {
            Future<Long> draining;
            try {
                draining = worker.submit(() -> drainUntil(relay(candidate, () -> { }),
                    () -> count("status = 'PENDING'") == 0));
                Thread.sleep(10_000);
            } finally {
                BROKER.resume();
            }
            millis = draining.get(LIMIT.toSeconds(), TimeUnit.SECONDS);
        } finally {
            worker.shutdownNow();
        }

        assertThat(count("status = 'PUBLISHED'")).isEqualTo(3_000);
        report("brokerPause", candidate, Map.of("rows", 3_000, "millisUntilDrained", millis,
            "duplicateRecords", endOffsetSum() - recordsBefore - 3_000,
            "rowsWithRetry", count("attempt_count > 1")));
    }

    // ---- relay ----

    private interface Round {
        int run();
    }

    private Round relay(Candidate candidate, Runnable afterSends) {
        return switch (candidate) {
            case SERIAL -> new OrderOutboxRelay(publisher, transactions, BATCH_SIZE, FIRST_RETRY, MAX_RETRY)::runOnce;
            case BATCHED -> new BatchedOutboxRelay(jdbc, namedJdbc, transactionTemplate, kafka, transactions, TOPIC,
                BATCH_SIZE, SEND_WAIT, FIRST_RETRY, MAX_RETRY, afterSends)::runOnce;
        };
    }

    /**
     * 보낸 뒤 기록 전에 한 번 멈춘다. 지금 relay는 한 건씩 기록하므로 첫 행의 send가 성공한 직후 예외를 낸다.
     * 이 예외를 relay가 전송 실패로 받아 재시도로 남기므로, 그 행은 다시 나간다(프로세스가 멈췄다가 다시 뜬 경우와 같은 결과).
     * 후보는 회차의 보내기가 모두 끝난 뒤 예외를 내 회차 전체가 기록되지 않은 채 끝난다.
     */
    private void stopOnceAfterSend(Candidate candidate) {
        RuntimeException stop = new IllegalStateException("보낸 뒤 기록 전 멈춤 흉내");
        switch (candidate) {
            case SERIAL -> {
                var once = new AtomicBoolean(true);
                OrderConfirmationPublisher stopAfterFirst = (OrderOutboxDelivery delivery) -> {
                    publisher.publish(delivery);
                    if (once.getAndSet(false)) {
                        throw stop;
                    }
                };
                new OrderOutboxRelay(stopAfterFirst, transactions, BATCH_SIZE, Duration.ZERO, Duration.ZERO).runOnce();
            }
            case BATCHED -> {
                try {
                    relay(candidate, () -> {
                        throw stop;
                    }).run();
                } catch (IllegalStateException expected) {
                    assertThat(expected).isSameAs(stop);
                }
            }
        }
    }

    /** 조건을 만족할 때까지 회차를 쉬지 않고 돌린다. 보낼 행이 없으면 재시도 시각을 기다리며 잠깐 쉰다. */
    private long drainUntil(Round relay, BooleanSupplier done) throws InterruptedException {
        long started = System.nanoTime();
        while (!done.getAsBoolean()) {
            assertThat(Duration.ofNanos(System.nanoTime() - started)).as("제한 시간").isLessThan(LIMIT);
            if (relay.run() == 0) {
                Thread.sleep(50);
            }
        }
        return Duration.ofNanos(System.nanoTime() - started).toMillis();
    }

    // ---- 준비·관찰 ----

    private void insert(int rows) {
        for (int i = 0; i < rows; i++) {
            recorder.record(new OrderConfirmed(nextOrderId++, Instant.now().truncatedTo(ChronoUnit.MICROS),
                List.of(new OrderConfirmed.Item(7L, 1))));
        }
    }

    private long count(String condition) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM order_outbox WHERE " + condition, Long.class);
    }

    private static long endOffsetSum() throws Exception {
        try (AdminClient admin = admin()) {
            Map<TopicPartition, OffsetSpec> latest = new LinkedHashMap<>();
            for (int partition = 0; partition < PARTITIONS; partition++) {
                latest.put(new TopicPartition(TOPIC, partition), OffsetSpec.latest());
            }
            return admin.listOffsets(latest).all().get(10, TimeUnit.SECONDS).values().stream()
                .mapToLong(info -> info.offset()).sum();
        }
    }

    private static AdminClient admin() {
        return AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, BROKER.bootstrapServers()));
    }

    private static void report(String scenario, Candidate candidate, Map<String, Object> values) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("scenario", scenario);
        report.put("candidate", candidate);
        report.putAll(values);
        System.out.println("OUTBOX_BATCHED_RELAY_EXPERIMENT " + report);
    }
}
