package com.loopers.application.order;

import com.loopers.domain.order.OrderConfirmationPublisher;
import com.loopers.domain.order.OrderConfirmed;
import com.loopers.domain.order.OrderOutboxRecorder;
import com.loopers.domain.order.OrderOutboxStatus;
import com.loopers.infrastructure.order.OrderOutboxJpaRepository;
import com.loopers.testcontainers.KafkaTestBroker;
import com.loopers.utils.DatabaseCleanUp;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * relay 설정(한 회차 batch 건, 회차 사이 쉼)이 초당 몇 건까지 따라잡는지 잰다.
 * <p>
 * 처음 설정(20건, 1초)의 한계와, batch를 아주 크게 해도 넘지 못하는 직렬 전송의 상한(약 1 / 행당 처리 시간)을 본다.
 * <p>
 * relay의 처리량만 보는 실험이라 주문 API·PG를 거치지 않고, Outbox 기록기를 직접 불러 합성 사건을 넣는다.
 * relay 입장에서는 주문 확정이 남긴 행과 같은 PENDING 행이다. 스케줄러 대신 테스트 스레드가
 * fixedDelay처럼 "회차 실행 → 1초 쉼"을 반복한다. broker 1대·로컬 환경의 결과다.
 * 실행: {@code ./gradlew :apps:commerce-api:experimentTest --tests '*OutboxRelayThroughputExperimentTest'}
 */
@Tag("experiment")
@SpringBootTest(properties = "commerce.order-events.enabled=true")
class OutboxRelayThroughputExperimentTest {
    private static final KafkaTestBroker BROKER = new KafkaTestBroker();
    private static final String TOPIC = "order-outbox-throughput-" + UUID.randomUUID();
    private static final Duration LOAD = Duration.ofSeconds(30);
    private static final Duration FIXED_DELAY = Duration.ofSeconds(1);
    private static final int BATCH_SIZE = 20;
    /** 한 회차가 PENDING을 거의 다 가져가게 해, 간격이 아니라 행당 처리 시간이 한계를 정하게 한다. */
    private static final int HUGE_BATCH_SIZE = 5_000;
    private static final Duration DRAIN_LIMIT = Duration.ofSeconds(300);

    @DynamicPropertySource
    static void endpoints(DynamicPropertyRegistry registry) {
        registry.add("commerce.order-events.topic", () -> TOPIC);
        registry.add("spring.kafka.bootstrap-servers", BROKER::bootstrapServers);
    }

    @Autowired private OrderOutboxRecorder recorder;
    @Autowired private OrderOutboxJpaRepository outbox;
    @Autowired private OrderOutboxTransactions transactions;
    @Autowired private OrderConfirmationPublisher publisher;
    @Autowired private DatabaseCleanUp cleanUp;
    @Autowired private JdbcTemplate jdbc;

    @BeforeAll
    static void createTopic() throws Exception {
        try (AdminClient admin = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
            BROKER.bootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(TOPIC, 1, (short) 1))).all().get(10, TimeUnit.SECONDS);
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

    @ParameterizedTest(name = "초당 {0}건")
    @ValueSource(ints = {10, 40})
    void relayKeepsUpOnlyBelowItsRoundCapacity(int perSecond) throws Exception {
        measure(perSecond, BATCH_SIZE, FIXED_DELAY);
    }

    @ParameterizedTest(name = "초당 {0}건, batch 5000")
    @ValueSource(ints = {200})
    void serialRelayStopsAtPerRowCeilingEvenWithHugeBatch(int perSecond) throws Exception {
        measure(perSecond, HUGE_BATCH_SIZE, FIXED_DELAY);
    }

    private void measure(int perSecond, int batchSize, Duration fixedDelay) throws Exception {
        var relay = new OrderOutboxRelay(publisher, transactions, batchSize, Duration.ofSeconds(2), Duration.ofSeconds(60));
        var loading = new AtomicBoolean(true);
        var stop = new AtomicBoolean(false);
        var drainedAt = new AtomicLong();
        var sentRows = new AtomicLong();
        var sendingNanos = new AtomicLong();
        List<long[]> samples = new CopyOnWriteArrayList<>(); // (경과 ms, PENDING 수)
        ExecutorService workers = Executors.newFixedThreadPool(2);
        long started = System.nanoTime();

        // relay: fixedDelay처럼 "회차 실행 → 쉼"을 반복한다. 유입이 끝난 뒤 PENDING 0을 확인한 시각을 직접 남긴다.
        Future<?> relayLoop = workers.submit(() -> {
            while (!stop.get()) {
                long roundStarted = System.nanoTime();
                int sent = relay.runOnce();
                sendingNanos.addAndGet(System.nanoTime() - roundStarted);
                sentRows.addAndGet(sent);
                if (!loading.get() && pendingCount() == 0) {
                    drainedAt.set(System.nanoTime());
                    return null;
                }
                Thread.sleep(fixedDelay.toMillis());
            }
            return null;
        });
        Future<?> sampler = workers.submit(() -> {
            while (!stop.get() && !relayLoop.isDone()) {
                samples.add(new long[] {Duration.ofNanos(System.nanoTime() - started).toMillis(), pendingCount()});
                try {
                    Thread.sleep(1_000);
                } catch (InterruptedException stopRequested) {
                    return null; // 정리 단계의 shutdownNow는 정상 종료 신호다
                }
            }
            return null;
        });

        int total = (int) (perSecond * LOAD.toSeconds());
        long loadEnded;
        long pendingAtLoadEnd;
        try {
            for (int i = 0; i < total; i++) {
                long wait = started + Duration.ofSeconds(1).toNanos() * i / perSecond - System.nanoTime();
                if (wait > 0) {
                    TimeUnit.NANOSECONDS.sleep(wait);
                }
                recorder.record(new OrderConfirmed(1_000_000L + i, Instant.now().truncatedTo(ChronoUnit.MICROS),
                    List.of(new OrderConfirmed.Item(7L, 1))));
            }
            loadEnded = System.nanoTime();
            pendingAtLoadEnd = pendingCount();
            loading.set(false);
            relayLoop.get(DRAIN_LIMIT.toMillis(), TimeUnit.MILLISECONDS); // relay 예외도 여기서 시험 스레드로 전파된다
        } finally {
            // 실패해도 worker를 멈춘 뒤에만 DB를 정리한다(@AfterEach).
            stop.set(true);
            workers.shutdownNow();
            assertThat(workers.awaitTermination(30, TimeUnit.SECONDS)).as("relay·sampler 종료").isTrue();
        }
        sampler.get(1, TimeUnit.SECONDS);

        List<Long> latencies = outbox.findAll().stream()
            .filter(row -> row.getStatus() == OrderOutboxStatus.PUBLISHED)
            .map(row -> Duration.between(row.getCreatedAt().toInstant(), row.getPublishedAt()).toMillis())
            .sorted().toList();
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("perSecond", perSecond);
        report.put("batchSize", batchSize);
        report.put("fixedDelayMillis", fixedDelay.toMillis());
        report.put("inserted", total);
        report.put("published", latencies.size());
        report.put("loadMillis", Duration.ofNanos(loadEnded - started).toMillis());
        report.put("pendingAtLoadEnd", pendingAtLoadEnd);
        report.put("maxPending", samples.stream().mapToLong(sample -> sample[1]).max().orElse(0));
        report.put("drainAfterLoadMillis", Duration.ofNanos(drainedAt.get() - loadEnded).toMillis());
        report.put("latencyAvgMillis", latencies.stream().mapToLong(Long::longValue).average().orElse(0));
        report.put("latencyP95Millis", percentile(latencies, 0.95));
        report.put("latencyMaxMillis", latencies.isEmpty() ? 0 : latencies.getLast());
        report.put("throughputUntilDrainedPerSecond", total * 1_000.0 / Duration.ofNanos(drainedAt.get() - started).toMillis());
        report.put("rowProcessingMillis", sentRows.get() == 0 ? 0 : Duration.ofNanos(sendingNanos.get()).toMillis()
            / (double) sentRows.get());
        System.out.println("OUTBOX_THROUGHPUT_EXPERIMENT " + report);

        assertThat(latencies).hasSize(total);
    }

    /** 수천 행을 엔티티로 읽지 않고 개수만 센다. 측정 중 DB에 주는 부하를 줄인다. */
    private long pendingCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM order_outbox WHERE status = 'PENDING'", Long.class);
    }

    private static long percentile(List<Long> sorted, double ratio) {
        if (sorted.isEmpty()) {
            return 0;
        }
        return sorted.get((int) Math.ceil(ratio * sorted.size()) - 1);
    }

}
