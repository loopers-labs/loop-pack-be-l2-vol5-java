package com.loopers.experiment;

import com.loopers.application.order.facade.OrderOutboxRelay;
import com.loopers.application.order.facade.OrderOutboxRelayProperties;
import com.loopers.application.order.facade.OrderOutboxRelaySteps;
import com.loopers.application.order.facade.OrderPaymentFacade;
import com.loopers.application.order.facade.OrderPaymentFacade.ConfirmCommand;
import com.loopers.domain.brand.entity.Brand;
import com.loopers.domain.order.entity.Order;
import com.loopers.domain.order.entity.OrderConfirmedOutbox;
import com.loopers.domain.order.gateway.OrderConfirmedSender;
import com.loopers.domain.order.gateway.OrderConfirmedSerializer;
import com.loopers.domain.order.vo.OrderConfirmed;
import com.loopers.domain.order.vo.OrderLine;
import com.loopers.domain.product.entity.Product;
import com.loopers.testcontainers.KafkaTestContainersConfig;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.testcontainers.containers.GenericContainer;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 7주차 D-108 relay 간격 값 재결정 실험. 값마다 relay 를 새로 만들어 @Scheduled(fixedDelay) 와 같은 의미로 돌림.
 * E1 평상시 확정 → 발행 지연, E2 backlog 소진 처리량, E3 broker 정지(docker pause) · 회복 뒤 따라잡는 시간.
 * 로컬 단일 broker 측정이라 운영 네트워크를 대표하지 않음. 결과는 build/experiment/relay-interval.md 에 남김.
 * 실행: RUN_EXPERIMENT=true ./gradlew :apps:commerce-api:test --tests '*OutboxRelayIntervalExperiment'
 */
@EnabledIfEnvironmentVariable(named = "RUN_EXPERIMENT", matches = "true")
@SpringBootTest(properties = "order-event.topic=order-events-relay-interval")
class OutboxRelayIntervalExperiment {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayIntervalExperiment.class);
    private static final Long USER_ID = 1L;
    private static final Path REPORT = Path.of("build", "experiment", "relay-interval.md");
    private static final Duration ARRIVAL_PERIOD = Duration.ofMillis(100);
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final Duration BACKOFF_INITIAL = Duration.ofSeconds(1);
    private static final int BACKLOG_ROWS = 2_000;
    // 회복으로 보는 기준: 가장 오래 기다린 PENDING 이 이만큼 안쪽
    private static final Duration CAUGHT_UP_AGE = Duration.ofSeconds(2);

    @Autowired
    private OrderOutboxRelaySteps steps;

    @Autowired
    private OrderConfirmedSender sender;

    @Autowired
    private OrderConfirmedSerializer serializer;

    @Autowired
    private Clock clock;

    @Autowired
    private OrderPaymentFacade orderPaymentFacade;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private Product product;

    @BeforeEach
    void setUp() {
        product = seedProduct();
    }

    @AfterEach
    void tearDown() {
        KafkaTestContainersConfig.container().ifPresent(OutboxRelayIntervalExperiment::unpause);
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("E1 평상시: 초당 10건 확정을 30초 동안 넣을 때 간격별 확정 → 발행 지연과 회차 수")
    @Test
    void steadyLatency() throws Exception {
        report("## E1 평상시 확정 → 발행 지연 (초당 10건 · 30초, 묶음 50)", "",
            "| 간격 | 확정 수 | p50 ms | p95 ms | max ms | 회차 수 | 초당 회차 |", "|---|---|---|---|---|---|---|");
        for (Duration interval : List.of(Duration.ofMillis(200), Duration.ofSeconds(1), Duration.ofSeconds(5))) {
            resetData();
            AtomicInteger rounds = new AtomicInteger();
            ScheduledExecutorService relay = startRelay(properties(50, Duration.ofMinutes(5)), interval, rounds);
            long started = System.nanoTime();
            List<Long> confirmMillis = confirmFor(Duration.ofSeconds(30));
            awaitUntil(() -> pendingCount() == 0, Duration.ofSeconds(60));
            double elapsedSeconds = (System.nanoTime() - started) / 1e9;
            stop(relay);

            List<Long> latencies = publishLatenciesMillis();
            assertThat(latencies).hasSize(confirmMillis.size());
            report("| %s | %d | %d | %d | %d | %d | %.2f |".formatted(interval, latencies.size(), percentile(latencies, 50),
                percentile(latencies, 95), percentile(latencies, 100), rounds.get(), rounds.get() / elapsedSeconds));
        }
        report("");
    }

    @DisplayName("E2 backlog: PENDING 2,000건을 묶음 · 간격 조합별로 비우는 시간과 처리량")
    @Test
    void backlogDrain() throws Exception {
        report("## E2 backlog 소진 (PENDING " + BACKLOG_ROWS + "건)", "",
            "| 묶음 | 간격 | 소진 s | 건/s | 회차 수 | 행당 ms (간격 제외) |", "|---|---|---|---|---|---|");
        for (int batchSize : List.of(10, 50, 200)) {
            for (Duration interval : List.of(Duration.ofMillis(200), Duration.ofSeconds(1))) {
                resetData();
                insertPending(BACKLOG_ROWS);
                AtomicInteger rounds = new AtomicInteger();
                long started = System.nanoTime();
                ScheduledExecutorService relay = startRelay(properties(batchSize, Duration.ofMinutes(5)), interval, rounds);
                awaitUntil(() -> pendingCount() == 0, Duration.ofMinutes(6));
                double seconds = (System.nanoTime() - started) / 1e9;
                stop(relay);

                assertThat(publishedCount()).isEqualTo(BACKLOG_ROWS);
                double perRowMillis = (seconds - Math.max(rounds.get() - 1, 0) * interval.toMillis() / 1000.0) * 1000 / BACKLOG_ROWS;
                report("| %d | %s | %.1f | %.1f | %d | %.2f |".formatted(batchSize, interval, seconds, BACKLOG_ROWS / seconds,
                    rounds.get(), perRowMillis));
            }
        }
        report("");
    }

    @DisplayName("E3 broker 정지 · 회복: 초당 10건 확정을 계속 넣으며 broker 를 멈췄다 풀고, backoff 최대값별로 따라잡는 시간")
    @Test
    void outageRecovery() throws Exception {
        reportOutageHeader("## E3 broker 정지 · 회복 (간격 1s · 묶음 50, 확정 초당 10건 계속)");
        for (Duration outage : List.of(Duration.ofSeconds(60), Duration.ofSeconds(180))) {
            for (Duration backoffMax : List.of(Duration.ofSeconds(30), Duration.ofMinutes(1), Duration.ofMinutes(5))) {
                runOutage(outage, 50, backoffMax);
            }
        }
        report("");
    }

    @DisplayName("E4 후보 값 확인: E2 · E3 에서 고른 묶음 200 · backoff 최대 1분으로 E3 를 다시 잼")
    @Test
    void outageRecoveryWithCandidate() throws Exception {
        reportOutageHeader("## E4 후보 값 확인 (간격 1s · 묶음 200 · backoff 최대 1분, 확정 초당 10건 계속)");
        for (Duration outage : List.of(Duration.ofSeconds(60), Duration.ofSeconds(180))) {
            runOutage(outage, 200, Duration.ofMinutes(1));
        }
        report("");
    }

    private void reportOutageHeader(String title) throws IOException {
        report(title, "",
            "| 정지 | 묶음 | backoff 최대 | 회복 뒤 따라잡음 s | 정지 중 확정 | 정지 중 회차 | 최대 attempt | 최대 다음 시도까지 s | 확정 p95 ms (평상 → 정지 중) |",
            "|---|---|---|---|---|---|---|---|---|");
    }

    private void runOutage(Duration outage, int batchSize, Duration backoffMax) throws Exception {
        GenericContainer<?> kafka = KafkaTestContainersConfig.container()
            .orElseThrow(() -> new IllegalStateException("Testcontainers broker 로만 실행함"));
        resetData();
        AtomicInteger rounds = new AtomicInteger();
        ScheduledExecutorService relay = startRelay(properties(batchSize, backoffMax), Duration.ofSeconds(1), rounds);
        ArrivalLoad load = ArrivalLoad.start(this::confirmOne);
        Thread.sleep(10_000);
        List<Long> normalConfirm = load.drain();

        pause(kafka);
        int roundsBeforeOutage = rounds.get();
        Thread.sleep(outage.toMillis());
        List<Long> outageConfirm = load.drain();
        int outageRounds = rounds.get() - roundsBeforeOutage;
        int maxAttempt = maxAttempt();
        long maxWaitSeconds = maxNextAttemptSeconds();
        unpause(kafka);

        long recovered = System.nanoTime();
        awaitUntil(() -> oldestPendingAge().compareTo(CAUGHT_UP_AGE) <= 0, Duration.ofMinutes(7));
        double catchUpSeconds = (System.nanoTime() - recovered) / 1e9;
        load.stop();
        awaitUntil(() -> pendingCount() == 0, Duration.ofMinutes(2));
        stop(relay);

        assertThat(blockedCount()).isZero();
        assertThat(publishedCount()).isEqualTo(confirmedCount());
        report("| %s | %d | %s | %.1f | %d | %d | %d | %d | %d → %d |".formatted(outage, batchSize, backoffMax, catchUpSeconds,
            outageConfirm.size(), outageRounds, maxAttempt, maxWaitSeconds,
            percentile(normalConfirm, 95), percentile(outageConfirm, 95)));
    }

    private OrderOutboxRelayProperties properties(int batchSize, Duration backoffMax) {
        return new OrderOutboxRelayProperties(batchSize, LEASE, BACKOFF_INITIAL, backoffMax);
    }

    /** @Scheduled(fixedDelay) 와 같이 앞 회차가 끝난 뒤 간격만큼 쉼 */
    private ScheduledExecutorService startRelay(OrderOutboxRelayProperties properties, Duration interval, AtomicInteger rounds) {
        OrderOutboxRelay relay = new OrderOutboxRelay(steps, sender, clock, properties);
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        executor.scheduleWithFixedDelay(() -> {
            try {
                rounds.incrementAndGet();
                relay.runOnce();
            } catch (RuntimeException e) {
                // 예외가 나가면 스케줄이 멈추므로 기록만 함
                log.error("relay round failed", e);
            }
        }, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
        return executor;
    }

    private static void stop(ScheduledExecutorService executor) throws InterruptedException {
        executor.shutdown();
        assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
    }

    /** 0원 상품이라 PG 없이 확정 TX 에서 Outbox 가 기록됨 */
    private long confirmOne() {
        Order order = transactionTemplate.execute(status -> {
            Order draft = Order.draft(USER_ID, List.of(new OrderLine(product.getId(), product.getName(), product.getPrice(), 1)),
                ZonedDateTime.now(clock));
            entityManager.persist(draft);
            return draft;
        });
        long started = System.nanoTime();
        orderPaymentFacade.confirm(USER_ID, order.getId(), new ConfirmCommand("attempt-" + order.getId(), "TEST", "0000-0000-0000-0000"));
        return (System.nanoTime() - started) / 1_000_000;
    }

    private List<Long> confirmFor(Duration duration) throws InterruptedException {
        ArrivalLoad load = ArrivalLoad.start(this::confirmOne);
        Thread.sleep(duration.toMillis());
        load.stop();
        return load.drain();
    }

    /** 확정 사건과 같은 모양의 PENDING 행을 바로 넣음. relay 는 주문을 읽지 않음 */
    private void insertPending(int rows) {
        for (int from = 0; from < rows; from += 500) {
            int start = from;
            transactionTemplate.executeWithoutResult(status -> {
                ZonedDateTime now = ZonedDateTime.now(clock);
                for (int i = start; i < Math.min(start + 500, rows); i++) {
                    long orderId = 1_000_000L + i;
                    OrderConfirmed event = new OrderConfirmed("order-confirmed:v1:" + orderId, orderId, now,
                        List.of(new OrderConfirmed.Item(product.getId(), 1)));
                    entityManager.persist(OrderConfirmedOutbox.pending(event, serializer.serialize(event), now));
                }
            });
        }
    }

    private Product seedProduct() {
        return transactionTemplate.execute(status -> {
            Brand brand = new Brand("브랜드", null);
            entityManager.persist(brand);
            Product saved = new Product(brand, "상품", 0L);
            saved.changeStock(100_000);
            entityManager.persist(saved);
            return saved;
        });
    }

    private void resetData() {
        databaseCleanUp.truncateAllTables();
        product = seedProduct();
    }

    private List<Long> publishLatenciesMillis() {
        List<Long> latencies = longs("SELECT TIMESTAMPDIFF(MICROSECOND, o.confirmed_at, x.published_at) DIV 1000 "
            + "FROM order_confirmed_outbox x JOIN orders o ON o.id = x.order_id WHERE x.status = 'PUBLISHED'");
        Collections.sort(latencies);
        return latencies;
    }

    private Duration oldestPendingAge() {
        // 시각은 UTC 로 저장됨 (jpa.yml jdbc.time_zone)
        List<Long> ages = longs("SELECT TIMESTAMPDIFF(MICROSECOND, MIN(o.confirmed_at), UTC_TIMESTAMP(6)) DIV 1000 "
            + "FROM order_confirmed_outbox x JOIN orders o ON o.id = x.order_id WHERE x.status = 'PENDING'");
        return ages.isEmpty() || ages.get(0) == null ? Duration.ZERO : Duration.ofMillis(ages.get(0));
    }

    private int pendingCount() {
        return count("SELECT COUNT(*) FROM order_confirmed_outbox WHERE status = 'PENDING'");
    }

    private int publishedCount() {
        return count("SELECT COUNT(*) FROM order_confirmed_outbox WHERE status = 'PUBLISHED'");
    }

    private int blockedCount() {
        return count("SELECT COUNT(*) FROM order_confirmed_outbox WHERE status = 'BLOCKED'");
    }

    private int confirmedCount() {
        return count("SELECT COUNT(*) FROM orders WHERE status = 'CONFIRMED'");
    }

    private int maxAttempt() {
        return count("SELECT COALESCE(MAX(attempt_count), 0) FROM order_confirmed_outbox");
    }

    /** 정지 중 가장 멀리 밀린 행이 다음 시도까지 남은 초. 회복 뒤 꼬리의 상한 */
    private long maxNextAttemptSeconds() {
        List<Long> seconds = longs("SELECT TIMESTAMPDIFF(SECOND, UTC_TIMESTAMP(6), MAX(next_attempt_at)) "
            + "FROM order_confirmed_outbox WHERE status = 'PENDING' AND attempt_count > 0");
        return seconds.isEmpty() || seconds.get(0) == null ? 0 : seconds.get(0);
    }

    private int count(String sql) {
        return longs(sql).get(0).intValue();
    }

    @SuppressWarnings("unchecked")
    private List<Long> longs(String sql) {
        return transactionTemplate.execute(status -> ((List<Object>) entityManager.createNativeQuery(sql).getResultList()).stream()
            .map(value -> value == null ? null : ((Number) value).longValue())
            .collect(Collectors.toCollection(ArrayList::new)));
    }

    private static void awaitUntil(BooleanSupplier condition, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError(timeout + " 안에 조건을 만족하지 못함");
            }
            Thread.sleep(200);
        }
    }

    private static long percentile(List<Long> values, int percentile) {
        if (values.isEmpty()) {
            return 0;
        }
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int index = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    private static void pause(GenericContainer<?> container) {
        container.getDockerClient().pauseContainerCmd(container.getContainerId()).exec();
    }

    private static void unpause(GenericContainer<?> container) {
        Boolean paused = container.getDockerClient().inspectContainerCmd(container.getContainerId()).exec().getState().getPaused();
        if (Boolean.TRUE.equals(paused)) {
            container.getDockerClient().unpauseContainerCmd(container.getContainerId()).exec();
        }
    }

    private static void report(String... lines) throws IOException {
        Files.createDirectories(REPORT.getParent());
        Files.write(REPORT, List.of(lines), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /** 고정 주기로 확정을 하나씩 넣고 확정 응답 시간을 모음. 확정이 느려지면 주기가 밀림 */
    private static final class ArrivalLoad {

        private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        private final List<Long> confirmMillis = new CopyOnWriteArrayList<>();

        static ArrivalLoad start(LongSupplier confirm) {
            ArrivalLoad load = new ArrivalLoad();
            load.executor.scheduleAtFixedRate(() -> {
                try {
                    load.confirmMillis.add(confirm.getAsLong());
                } catch (RuntimeException e) {
                    log.error("confirm failed", e);
                }
            }, 0, ARRIVAL_PERIOD.toMillis(), TimeUnit.MILLISECONDS);
            return load;
        }

        /** 지금까지 모은 응답 시간을 꺼내고 비움 */
        List<Long> drain() {
            List<Long> drained = new ArrayList<>(confirmMillis);
            confirmMillis.removeAll(drained);
            return drained;
        }

        void stop() throws InterruptedException {
            executor.shutdown();
            assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }
}
