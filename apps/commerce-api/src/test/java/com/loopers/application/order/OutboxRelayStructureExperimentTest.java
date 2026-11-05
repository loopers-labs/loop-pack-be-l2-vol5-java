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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * relay 구조를 바꾸면 처리량이 얼마나 달라지는지, 미리 쌓아 둔 PENDING을 다 비우는 속도로 비교한다.
 * <p>
 * 제품 relay는 그대로 두고, 후보 구조는 이 실험 안에서만 만든다. 후보는 성공 경로만 구현했다.
 * 일부만 실패했을 때의 처리(재시도 기록, 순서)는 비교하지 않는다. 들어오는 쓰기와 섞이지 않도록 유입 없이 비우기만 잰다.
 * 테스트 프로필은 SQL을 콘솔에 찍으므로 운영과 같은 조건(SQL 로그 끔)으로 잰다. 켠 조건은 하위 클래스가 잰다.
 * 실행: {@code ./gradlew :apps:commerce-api:experimentTest --tests '*OutboxRelayStructure*'}
 */
@Tag("experiment")
@SpringBootTest(properties = {"commerce.order-events.enabled=true", "spring.jpa.show-sql=false"})
class OutboxRelayStructureExperimentTest {
    /** 하위 클래스와 함께 쓰므로 클래스가 끝날 때 닫지 않는다. JVM 종료 때 Testcontainers가 정리한다. */
    private static final KafkaTestBroker BROKER = new KafkaTestBroker();
    private static final String TOPIC = "order-outbox-structure-" + UUID.randomUUID();
    private static final int PARTITIONS = 3;
    private static final int ROWS = 3_000;
    private static final int WARMUP_ROWS = 300;
    private static final int REPEATS = 3;
    private static final int BATCH_SIZE = 500;
    private static final int PARALLEL_RELAYS = 3;
    private static final Duration SEND_WAIT = Duration.ofSeconds(130);
    private static final Duration DRAIN_LIMIT = Duration.ofSeconds(300);

    @DynamicPropertySource
    static void endpoints(DynamicPropertyRegistry registry) {
        registry.add("commerce.order-events.topic", () -> TOPIC);
        registry.add("spring.kafka.bootstrap-servers", BROKER::bootstrapServers);
    }

    /** 비교할 relay 구조. */
    enum Structure {
        /** 지금 제품 relay. 행마다 시도 기록 TX → 보내고 응답 대기 → PUBLISHED TX. */
        SERIAL,
        /** 행마다 시도 기록 TX를 한 뒤 회차의 행을 모두 보내고, 응답을 모아 기다린 다음 행마다 PUBLISHED TX. */
        ASYNC_SEND,
        /** 회차의 시도 기록을 UPDATE 한 번, 모두 보내고 응답을 모아 기다린 뒤 PUBLISHED도 UPDATE 한 번. */
        ASYNC_SEND_BULK_DB,
        /** 지금 relay의 행 처리를 3개가 나눠 맡는다. SKIP LOCKED로 서로 다른 행을 집는 relay 3대를 흉내 내며, 잠금 조회 비용은 빠진다. */
        PARALLEL_SERIAL
    }

    @Autowired private OrderOutboxRecorder recorder;
    @Autowired private OrderOutboxTransactions transactions;
    @Autowired private OrderConfirmationPublisher publisher;
    @Autowired private KafkaTemplate<Object, Object> kafka;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private NamedParameterJdbcTemplate namedJdbc;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private DatabaseCleanUp cleanUp;
    @Value("${spring.jpa.show-sql}") private boolean showSql;

    private long nextOrderId = 3_000_000L;

    /** 하위 클래스도 같은 broker·topic을 쓴다. 두 번째 클래스에서는 이미 있는 topic을 그대로 쓴다. */
    @BeforeAll
    static void createTopic() throws Exception {
        try (AdminClient admin = admin()) {
            if (!admin.listTopics().names().get(10, TimeUnit.SECONDS).contains(TOPIC)) {
                admin.createTopics(List.of(new NewTopic(TOPIC, PARTITIONS, (short) 1))).all().get(10, TimeUnit.SECONDS);
            }
        }
    }

    @AfterEach
    void tearDown() {
        cleanUp.truncateAllTables();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Structure.class)
    void drainCapacity(Structure structure) throws Exception {
        drain(structure, WARMUP_ROWS, new Timings()); // JIT·연결 풀·producer metadata를 데운다. 결과는 버린다.
        cleanUp.truncateAllTables();

        List<Double> perSecond = new ArrayList<>();
        Timings timings = new Timings();
        for (int repeat = 0; repeat < REPEATS; repeat++) {
            long recordsBefore = endOffsetSum();
            long elapsedNanos = drain(structure, ROWS, timings);
            perSecond.add(ROWS * 1e9 / elapsedNanos);

            assertThat(count("status = 'PENDING'")).as("PENDING이 남지 않음").isZero();
            assertThat(count("status = 'PUBLISHED' AND attempt_count = 1")).as("모두 한 번 시도로 PUBLISHED").isEqualTo(ROWS);
            assertThat(endOffsetSum() - recordsBefore).as("Kafka에 행 수만큼만 기록").isEqualTo(ROWS);
            cleanUp.truncateAllTables();
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("structure", structure);
        report.put("showSql", showSql);
        report.put("rows", ROWS);
        report.put("batchSize", BATCH_SIZE);
        report.put("perSecondRuns", perSecond.stream().map(value -> Math.round(value)).toList());
        report.put("perSecondAvg", Math.round(perSecond.stream().mapToDouble(Double::doubleValue).average().orElse(0)));
        report.put("rowMillisAvg", round(1_000.0 / perSecond.stream().mapToDouble(Double::doubleValue).average().orElse(1)));
        if (structure == Structure.SERIAL) {
            // 지금 relay의 행당 시간을 나눈다. 보내기 = publish 호출(응답 대기·로그 포함), 나머지 = DB TX·회차 조회.
            long rows = (long) ROWS * REPEATS;
            report.put("sendMillisPerRow", round(timings.sendNanos.get() / 1e6 / rows));
            report.put("otherMillisPerRow", round((timings.totalNanos.get() - timings.sendNanos.get()) / 1e6 / rows));
        }
        System.out.println("OUTBOX_RELAY_STRUCTURE_EXPERIMENT " + report);
    }

    /** PENDING 행을 넣고, 구조별 relay를 회차 사이 쉼 없이 돌려 다 비울 때까지의 시간을 돌려준다. */
    private long drain(Structure structure, int rows, Timings timings) throws Exception {
        for (int i = 0; i < rows; i++) {
            recorder.record(new OrderConfirmed(nextOrderId++, Instant.now().truncatedTo(ChronoUnit.MICROS),
                List.of(new OrderConfirmed.Item(7L, 1))));
        }
        TimingPublisher timingPublisher = new TimingPublisher(publisher, timings.sendNanos);
        var serialRelay = new OrderOutboxRelay(timingPublisher, transactions, BATCH_SIZE,
            Duration.ofSeconds(2), Duration.ofSeconds(60));
        ExecutorService parallel = Executors.newFixedThreadPool(PARALLEL_RELAYS);
        long started = System.nanoTime();
        try {
            int sent;
            do {
                sent = switch (structure) {
                    case SERIAL -> serialRelay.runOnce();
                    case ASYNC_SEND -> asyncSendRound();
                    case ASYNC_SEND_BULK_DB -> asyncSendBulkDbRound();
                    case PARALLEL_SERIAL -> parallelSerialRound(parallel);
                };
                assertThat(Duration.ofNanos(System.nanoTime() - started)).as("비우기 제한 시간").isLessThan(DRAIN_LIMIT);
            } while (sent > 0);
        } finally {
            parallel.shutdownNow();
        }
        long elapsed = System.nanoTime() - started;
        timings.totalNanos.addAndGet(elapsed);
        return elapsed;
    }

    private int asyncSendRound() throws Exception {
        List<Long> ids = transactions.findDueIds(BATCH_SIZE);
        List<CompletableFuture<SendResult<Object, Object>>> sends = new ArrayList<>();
        for (Long id : ids) {
            OrderOutboxDelivery delivery = transactions.startAttempt(id);
            sends.add(kafka.send(TOPIC, delivery.messageKey(), delivery.payload()));
        }
        for (int i = 0; i < ids.size(); i++) {
            sends.get(i).get(SEND_WAIT.toMillis(), TimeUnit.MILLISECONDS);
            transactions.markPublished(ids.get(i));
        }
        return ids.size();
    }

    private int asyncSendBulkDbRound() throws Exception {
        List<Map<String, Object>> rows = transactionTemplate.execute(status -> {
            List<Map<String, Object>> due = jdbc.queryForList("""
                SELECT id, message_key, payload FROM order_outbox
                WHERE status = 'PENDING' AND next_attempt_at <= ? ORDER BY id LIMIT ?""",
                Timestamp.from(Instant.now()), BATCH_SIZE);
            if (!due.isEmpty()) {
                namedJdbc.update("UPDATE order_outbox SET attempt_count = attempt_count + 1, updated_at = :now WHERE id IN (:ids)",
                    new MapSqlParameterSource("ids", ids(due)).addValue("now", Timestamp.from(Instant.now())));
            }
            return due;
        });
        if (rows.isEmpty()) {
            return 0;
        }
        List<CompletableFuture<SendResult<Object, Object>>> sends = rows.stream()
            .map(row -> kafka.send(TOPIC, row.get("message_key"), row.get("payload"))).toList();
        for (var send : sends) {
            send.get(SEND_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        }
        Timestamp now = Timestamp.from(Instant.now().truncatedTo(ChronoUnit.MICROS));
        namedJdbc.update("""
            UPDATE order_outbox SET status = 'PUBLISHED', published_at = :now, last_error = NULL, updated_at = :now
            WHERE id IN (:ids)""", new MapSqlParameterSource("ids", ids(rows)).addValue("now", now));
        return rows.size();
    }

    private int parallelSerialRound(ExecutorService parallel) throws Exception {
        List<Long> ids = transactions.findDueIds(BATCH_SIZE);
        List<Future<?>> slices = IntStream.range(0, PARALLEL_RELAYS).<Future<?>>mapToObj(slice -> parallel.submit(() -> {
            for (int i = slice; i < ids.size(); i += PARALLEL_RELAYS) {
                Long id = ids.get(i);
                publisher.publish(transactions.startAttempt(id));
                transactions.markPublished(id);
            }
        })).toList();
        for (Future<?> slice : slices) {
            slice.get(SEND_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        }
        return ids.size();
    }

    private static List<Long> ids(List<Map<String, Object>> rows) {
        return rows.stream().map(row -> ((Number) row.get("id")).longValue()).toList();
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

    private static double round(double value) {
        return Math.round(value * 100) / 100.0;
    }

    /** 측정 반복을 합친 시간. */
    private static final class Timings {
        private final AtomicLong totalNanos = new AtomicLong();
        private final AtomicLong sendNanos = new AtomicLong();
    }

    /** 제품 publisher를 감싸 publish 호출 시간만 더한다. */
    private record TimingPublisher(OrderConfirmationPublisher delegate, AtomicLong sendNanos)
        implements OrderConfirmationPublisher {
        @Override
        public void publish(OrderOutboxDelivery delivery) {
            long started = System.nanoTime();
            try {
                delegate.publish(delivery);
            } finally {
                sendNanos.addAndGet(System.nanoTime() - started);
            }
        }
    }
}
