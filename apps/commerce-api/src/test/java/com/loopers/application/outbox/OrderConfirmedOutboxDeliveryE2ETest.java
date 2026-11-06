package com.loopers.application.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.order.OrderConfirmedPublisher;
import com.loopers.application.order.OrderFacade;
import com.loopers.application.order.OrderInfo;
import com.loopers.application.order.SentRecord;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.outbox.OutboxEvent;
import com.loopers.domain.outbox.OutboxEventRepository;
import com.loopers.domain.outbox.OutboxStatus;
import com.loopers.domain.payment.PaymentAttemptRepository;
import com.loopers.domain.payment.PaymentAttemptStatus;
import com.loopers.infrastructure.order.OrderJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.support.order.PgOrderFlow;
import com.loopers.support.order.PgOrderFlow.PlacedOrder;
import com.loopers.support.process.BootJarProcess;
import com.loopers.testcontainers.KafkaTestContainers;
import com.loopers.utils.DatabaseCleanUp;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * 주문 → Outbox → broker → 구매 활동까지 한 번에 본다(docs/week7/decisions.md D-15, 과제 7번). 실제 MySQL·Kafka(테스트 컨테이너),
 * 테스트 PG, 그리고 현재 streamer 코드로 만든 실행 jar를 별도 프로세스로 띄운 consumer를 쓴다(D-10).
 *
 * <p>장면: 주문(상품 A × 2, 상품 B × 1)을 실제 결제 경로로 확정한다 → relay가 broker 확인까지 끝낸 뒤 PUBLISHED 저장 전에 멈춘다 →
 * 새 relay가 임대가 지난 뒤 같은 eventId를 다시 보낸다 → topic에 같은 eventId가 두 offset에 실린다 → consumer가 둘 다 받고도
 * 구매 활동은 한 번만 남긴다(W6 D-18 중복 처리). at-least-once의 중복을 consumer가 받는다는 것을 실제 두 프로세스로 확인한다.
 *
 * <p>이 테스트 하나가 자원을 모두 갖는다 — streamer 프로세스, 고유 topic·group, 새 사용자·상품·주문, 시계·결과 기록 래퍼.
 * - 시작할 때 api 테이블과 streamer 테이블을 비운다. 다른 클래스가 남긴 PENDING 행을 relay가 이 topic으로 보내지 않게 하고,
 *   api 테이블을 비우면 주문 id가 1부터 다시 쓰여 다른 테스트의 streamer가 남긴 처리 기록과 겹치기 때문이다.
 * - streamer는 같은 DB에 {@code ddl-auto=update}로 붙는다. api 테스트 컨텍스트가 이미 만든 스키마를 지우지 않고 자기 테이블만
 *   더한다(D-10 "첫 서버만 create").
 * - 자동 rollback 트랜잭션으로 감싸지 않는다. 기다림은 모두 상한이 있고, 실패하면 DB·Kafka 상태와 streamer 로그 끝부분을 출력한다.
 * - streamer jar 경로({@code streamer.bootJar})가 없으면 건너뛰지 않고 실패한다 — skip은 통과가 아니다(과제 조건).
 */
@SpringBootTest
@Import(OrderConfirmedOutboxDeliveryE2ETest.SeamConfig.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderConfirmedOutboxDeliveryE2ETest {

    private static final String TOPIC = "order-events-delivery-e2e-" + UUID.randomUUID();
    private static final String GROUP = "purchase-activity-delivery-e2e-" + UUID.randomUUID();
    private static final Duration WAIT = Duration.ofSeconds(60);
    private static final Duration STREAMER_READY = Duration.ofMinutes(3);
    private static final long USER_POINTS = 50_000L;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> STREAMER_TABLES = List.of(
        "processed_order_confirmation", "product_purchase_activity", "purchase_activity_conflict", "order_confirmed_contract_violation");

    @DynamicPropertySource
    static void topic(DynamicPropertyRegistry registry) {
        KafkaTestContainers.createTopic(TOPIC);
        registry.add("events.order-confirmed.topic", () -> TOPIC);
    }

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private TestClock clock;

    @Autowired
    private SwitchableLedger ledger;

    @Autowired
    private OrderConfirmedPublisher publisher;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Value("${outbox.relay.batch-size}")
    private int batchSize;

    @Value("${outbox.relay.lease-margin}")
    private Duration leaseMargin;

    @Autowired
    private OrderFacade orderFacade;

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Autowired
    private ProductJpaRepository productJpaRepository;

    @Autowired
    private OrderJpaRepository orderJpaRepository;

    @Autowired
    private PaymentAttemptRepository paymentAttemptRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @Autowired
    private DataSource dataSource;

    @DisplayName("주문(A×2, B×1) → PG SUCCESS 확정 → relay가 broker 확인 뒤 PUBLISHED 저장 전에 멈춤 → 새 relay가 임대 뒤 재발행: "
        + "topic에는 같은 eventId가 두 offset에 실리고, 별도 프로세스의 consumer는 둘 다 받고도 처리 기록 1건·구매 활동 2행(수량 2, 1)만 "
        + "남긴다. 재고·포인트·PG 거래는 더 바뀌지 않는다. (D-10, D-15, INV-701~703, W6 D-18)")
    @Test
    void duplicateDelivery_isAbsorbedByConsumer() throws Exception {
        String jar = System.getProperty("streamer.bootJar");
        assertThat(jar)
            .as("streamer 실행 jar 경로(시스템 프로퍼티 streamer.bootJar) — Gradle :apps:commerce-api:test가 넣는다. "
                + "IDE에서 돌릴 때는 :apps:commerce-streamer:bootJar 뒤 -Dstreamer.bootJar=<jar>를 준다")
            .isNotBlank();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        databaseCleanUp.truncateAllTables();
        truncateStreamerTables(jdbc);
        clock.restartAtRealNow();
        ledger.reset();

        try (BootJarProcess streamer = startStreamer(Path.of(jar))) {
            Scenario scenario = new Scenario(jdbc);
            try {
                scenario.run(streamer);
            } catch (Throwable failure) {
                scenario.printState(streamer);
                throw failure;
            }
        } finally {
            databaseCleanUp.truncateAllTables();
        }
    }

    // ---------------------------------------------------------------- streamer 프로세스

    private BootJarProcess startStreamer(Path jar) {
        String bootstrapServers = KafkaTestContainers.bootstrapServers();
        return BootJarProcess.builder("commerce-streamer-delivery-e2e", jar)
            .jvmArg("-Duser.timezone=" + System.getProperty("user.timezone"))
            .jvmArg("-Dstdout.encoding=UTF-8")
            .jvmArg("-Dstderr.encoding=UTF-8")
            .arg("spring.profiles.active", "test")
            .arg("server.port", "0")
            .arg("management.server.port", "0")
            // 같은 MySQL 컨테이너. 공용 픽스처(MySqlTestContainersConfig)가 이 JVM에 넣은 접속 정보를 그대로 넘긴다
            .arg("datasource.mysql-jpa.main.jdbc-url", requiredSystemProperty("datasource.mysql-jpa.main.jdbc-url"))
            .arg("datasource.mysql-jpa.main.username", requiredSystemProperty("datasource.mysql-jpa.main.username"))
            .arg("datasource.mysql-jpa.main.password", requiredSystemProperty("datasource.mysql-jpa.main.password"))
            // 이미 있는 스키마를 지우지 않는다 — 자기 테이블만 더한다(D-10)
            .arg("spring.jpa.hibernate.ddl-auto", "update")
            // 같은 Kafka 컨테이너. test 프로필의 localhost:19092·kafka:9092를 쓰지 않는다
            .arg("spring.kafka.bootstrap-servers", bootstrapServers)
            .arg("spring.kafka.admin.properties.bootstrap.servers", bootstrapServers)
            // 같은 Redis 컨테이너(RedisTestContainersInitializer가 이 JVM에 넣은 주소). streamer는 쓰지 않지만 공용 설정이 연결을 만든다
            .arg("datasource.redis.master.host", requiredSystemProperty("datasource.redis.master.host"))
            .arg("datasource.redis.master.port", requiredSystemProperty("datasource.redis.master.port"))
            .arg("datasource.redis.replicas[0].host", requiredSystemProperty("datasource.redis.replicas[0].host"))
            .arg("datasource.redis.replicas[0].port", requiredSystemProperty("datasource.redis.replicas[0].port"))
            .arg("events.order-confirmed.topic", TOPIC)
            .arg("events.order-confirmed.group-id", GROUP)
            .readyWhen(Pattern.compile("Started CommerceStreamerApplication"), STREAMER_READY)
            .start();
    }

    private static String requiredSystemProperty(String key) {
        String value = System.getProperty(key);
        assertThat(value).as("테스트 컨테이너 접속 정보 시스템 프로퍼티 %s", key).isNotBlank();
        return value;
    }

    /** streamer 테이블은 api 엔티티가 아니라 DatabaseCleanUp이 모른다. 있으면 비운다(첫 실행이면 streamer가 update로 만든다). */
    private static void truncateStreamerTables(JdbcTemplate jdbc) {
        List<String> existing = jdbc.queryForList(
            "select table_name from information_schema.tables where table_schema = database()", String.class);
        for (String table : STREAMER_TABLES) {
            if (existing.stream().anyMatch(table::equalsIgnoreCase)) {
                jdbc.execute("TRUNCATE TABLE `" + table + "`");
            }
        }
    }

    // ---------------------------------------------------------------- 장면

    /** 한 번의 실행. 실패 진단을 위해 지나온 값을 들고 있다. */
    private final class Scenario {

        private final JdbcTemplate jdbc;
        private final PgOrderFlow flow = new PgOrderFlow(orderFacade, userJpaRepository, productJpaRepository);
        private PlacedOrder order;

        Scenario(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        void run(BootJarProcess streamer) throws Exception {
            // 1. 실제 주문·결제 경로로 확정한다 — 상품 A × 2, 상품 B × 1(과제의 "7×2, 8×1" 모양), 결제는 PG
            order = flow.draft(USER_POINTS, 2, 1);
            Long productA = order.productIds().get(0);
            Long productB = order.productIds().get(1);
            flow.startPayment(order);
            OrderInfo confirmed = flow.applySuccess(order);

            // 2. 확정 TX가 PENDING 행을 정확히 하나 남겼다(INV-701). PG 거래는 하나다
            Order storedOrder = storedOrder();
            OutboxEvent pending = singleRow();
            System.out.println("[배달 E2E 1·2 확정] order=" + order + " / " + describe(pending));
            assertAll(
                () -> assertThat(confirmed.status()).isEqualTo(OrderStatus.CONFIRMED),
                () -> assertThat(storedOrder.getStatus()).isEqualTo(OrderStatus.CONFIRMED),
                () -> assertThat(attemptStatus()).isEqualTo(PaymentAttemptStatus.SUCCESS),
                () -> assertReservationConsumedOnce(productA, productB),
                () -> assertThat(pending.getEventId()).isEqualTo("order-confirmed:v1:" + order.orderId()),
                () -> assertThat(pending.getStatus()).isEqualTo(OutboxStatus.PENDING),
                () -> assertThat(pending.getAttemptCount()).isZero(),
                () -> assertThat(outboxEventRepository.findByEventId(order.eventId())).map(OutboxEvent::getId).contains(pending.getId()),
                () -> assertThat(flow.pgCreates()).as("PG 거래 생성 요청").isEqualTo(1),
                () -> assertThat(pointsOf()).isEqualTo(USER_POINTS)
            );

            // 3. 첫 relay: broker 확인까지 진짜로 끝나고, PUBLISHED 저장 호출에서 멈춘다(D-11 이유 2). 행은 테스트가 고치지 않는다
            ledger.mode(SwitchableLedger.Mode.HALT_ON_MARK_PUBLISHED);
            clock.catchUpToRealNow();
            OutboxRelayRound firstRound = relay.runOnce();
            OutboxEvent halted = singleRow();
            assertThat(ledger.halted()).as("broker가 확인한 첫 발행").hasSize(1);
            SentRecord firstSent = ledger.halted().get(0);
            System.out.println("[배달 E2E 3 중단] " + firstRound + " / firstSent=" + firstSent + " / " + describe(halted));
            assertAll(
                () -> assertThat(firstRound).isEqualTo(new OutboxRelayRound(false, 1, 1, 1, 0, 0)),
                () -> assertThat(firstSent.topic()).isEqualTo(TOPIC),
                () -> assertThat(halted.getStatus()).isEqualTo(OutboxStatus.PENDING),
                () -> assertThat(halted.getPublishedAt()).isNull(),
                () -> assertThat(halted.getAttemptCount()).isEqualTo(1)
            );

            // 4. 재시작: 같은 빈으로 새 relay를 만든다. 임대 중에는 고르지 않고, 시계를 임대 끝으로 넘기면 다시 보낸다
            ledger.mode(SwitchableLedger.Mode.PASS);
            OutboxRelay restarted = new OutboxRelay(outboxEventRepository, ledger, publisher, clock, batchSize, leaseMargin);
            assertThat(restarted.runOnce().due()).as("임대 중").isZero();
            clock.advanceTo(halted.getNextAttemptAt());
            OutboxRelayRound resent = restarted.runOnce();
            OutboxEvent published = singleRow();
            List<SentRecord> secondSent = ledger.publishedFor(published.getId());
            System.out.println("[배달 E2E 4 재발행] " + resent + " / secondSent=" + secondSent + " / " + describe(published));
            assertAll(
                () -> assertThat(resent).isEqualTo(new OutboxRelayRound(false, 1, 1, 1, 0, 0)),
                () -> assertThat(published.getStatus()).isEqualTo(OutboxStatus.PUBLISHED),
                () -> assertThat(published.getAttemptCount()).isEqualTo(2),
                () -> assertThat(published.getPayload()).isEqualTo(pending.getPayload()),
                () -> assertThat(secondSent).hasSize(1)
            );

            // 5. broker에서 다시 읽는다 — send 호출 수가 아니라 실제로 실린 record를 센다
            List<ConsumerRecord<byte[], byte[]>> records = recordsOfOrder();
            List<Long> offsets = records.stream().map(ConsumerRecord::offset).toList();
            System.out.println("[배달 E2E 5 topic] partition=" + firstSent.partition() + ", offsets=" + offsets);
            assertThat(records).as("같은 eventId의 record").hasSizeGreaterThanOrEqualTo(2);
            for (ConsumerRecord<byte[], byte[]> record : records) {
                assertThat(eventIdIn(record)).isEqualTo(order.eventId());
                assertThat(record.value()).isEqualTo(pending.getPayload().getBytes(StandardCharsets.UTF_8));
                assertThat(record.partition()).isEqualTo(firstSent.partition());
            }
            assertThat(offsets).doesNotHaveDuplicates().contains(firstSent.offset(), secondSent.get(0).offset());

            // 6. consumer group의 commit 위치가 마지막 record를 지날 때까지 기다린다 — consumer가 둘 다 받았다는 증거다
            TopicPartition partition = new TopicPartition(TOPIC, firstSent.partition());
            long lastOffset = offsets.stream().mapToLong(Long::longValue).max().orElseThrow();
            long committed = awaitCommittedPast(streamer, partition, lastOffset);
            System.out.println("[배달 E2E 6 consumer] group=" + GROUP + ", committed=" + committed + ", lastOffset=" + lastOffset);

            // 7. 공유 DB의 streamer 테이블 — 처리 기록 1건, 구매 활동 2행(수량 2, 1), conflict·계약 깨짐 없음
            Map<Long, Integer> activity = purchaseActivity();
            System.out.println("[배달 E2E 7 streamer] activity=" + activity);
            assertAll(
                () -> assertThat(count("select count(*) from processed_order_confirmation where order_id = ? and event_id = ?",
                    order.orderId(), order.eventId())).as("처리 기록").isEqualTo(1),
                () -> assertThat(count("select count(*) from processed_order_confirmation where order_id = ? or event_id = ?",
                    order.orderId(), order.eventId())).as("이 주문·eventId의 처리 기록 전체").isEqualTo(1),
                () -> assertThat(activity).containsExactlyInAnyOrderEntriesOf(Map.of(productA, 2, productB, 1)),
                () -> assertThat(count("select count(*) from purchase_activity_conflict where order_id = ? or event_id = ?",
                    order.orderId(), order.eventId())).as("conflict 기록").isZero(),
                () -> assertThat(count("select count(*) from purchase_activity_conflict where record_topic = ?", TOPIC))
                    .as("이 topic의 conflict 기록").isZero(),
                () -> assertThat(count("select count(*) from order_confirmed_contract_violation where record_topic = ?", TOPIC))
                    .as("이 topic의 계약 깨짐 기록").isZero()
            );

            // 8. 재발행은 보내는 쪽 일만 다시 했다 — 예약 소비·반환, 포인트 차감, PG 거래가 더 생기지 않았다
            Order after = storedOrder();
            assertAll(
                () -> assertThat(after.getStatus()).isEqualTo(OrderStatus.CONFIRMED),
                () -> assertThat(after.getConfirmedAt().toInstant()).isEqualTo(storedOrder.getConfirmedAt().toInstant()),
                () -> assertThat(attemptStatus()).isEqualTo(PaymentAttemptStatus.SUCCESS),
                () -> assertReservationConsumedOnce(productA, productB),
                () -> assertThat(pointsOf()).as("PG 주문의 포인트").isEqualTo(USER_POINTS),
                () -> assertThat(flow.pgCreates()).as("PG 거래 생성 요청").isEqualTo(1),
                () -> assertThat(streamer.isAlive()).as("streamer 프로세스").isTrue()
            );
        }

        private Order storedOrder() {
            return orderJpaRepository.findById(order.orderId()).orElseThrow();
        }

        private OutboxEvent singleRow() {
            List<OutboxEvent> rows = outboxEventRepository.findAllByOrderId(order.orderId());
            assertThat(rows).as("주문 %d의 Outbox 행", order.orderId()).hasSize(1);
            return rows.get(0);
        }

        private PaymentAttemptStatus attemptStatus() {
            return paymentAttemptRepository.findByOrderId(order.orderId()).orElseThrow().getStatus();
        }

        private long pointsOf() {
            return userJpaRepository.findById(order.userId()).orElseThrow().getPoint().getBalance();
        }

        /** 예약은 결제 시작에서 잡히고 SUCCESS는 그대로 소비한다 — "한 번 소비 = 처음 재고 − 수량 그대로". */
        private void assertReservationConsumedOnce(Long productA, Long productB) {
            assertThat(stockOf(productA)).as("상품 A 재고").isEqualTo(PgOrderFlow.INITIAL_STOCK - 2);
            assertThat(stockOf(productB)).as("상품 B 재고").isEqualTo(PgOrderFlow.INITIAL_STOCK - 1);
        }

        private int stockOf(Long productId) {
            return productJpaRepository.findById(productId).orElseThrow().getRemainingStock();
        }

        private List<ConsumerRecord<byte[], byte[]>> recordsOfOrder() {
            byte[] key = order.messageKey().getBytes(StandardCharsets.UTF_8);
            return allRecords().stream().filter(record -> Arrays.equals(record.key(), key)).toList();
        }

        private long awaitCommittedPast(BootJarProcess streamer, TopicPartition partition, long lastOffset) throws Exception {
            long deadline = System.nanoTime() + WAIT.toNanos();
            long committed = -1;
            while (System.nanoTime() < deadline) {
                assertThat(streamer.isAlive()).as("streamer 프로세스가 기다리는 동안 살아 있다").isTrue();
                committed = committedOffset(partition);
                if (committed > lastOffset) {
                    return committed;
                }
                Thread.sleep(500);
            }
            throw new AssertionError("consumer group " + GROUP + "의 " + partition + " commit 위치가 " + WAIT + " 안에 마지막 record("
                + lastOffset + ")를 지나지 않았다. 지금 commit 위치: " + committed);
        }

        private Map<Long, Integer> purchaseActivity() {
            Map<Long, Integer> quantities = new HashMap<>();
            jdbc.queryForList("select product_id, quantity from product_purchase_activity where order_id = ?", order.orderId())
                .forEach(row -> quantities.merge(((Number) row.get("product_id")).longValue(),
                    ((Number) row.get("quantity")).intValue(), Integer::sum));
            assertThat(count("select count(*) from product_purchase_activity where order_id = ?", order.orderId()))
                .as("구매 활동 행 수").isEqualTo(2);
            return quantities;
        }

        private int count(String sql, Object... args) {
            Integer count = jdbc.queryForObject(sql, Integer.class, args);
            return count == null ? 0 : count;
        }

        /** 실패했을 때 실제 상태를 남긴다. 진단 중의 오류는 원래 실패를 가리지 않게 삼킨다. */
        void printState(BootJarProcess streamer) {
            StringBuilder state = new StringBuilder("\n===== 배달 E2E 실패 진단 =====\n");
            state.append("topic=").append(TOPIC).append(", group=").append(GROUP).append(", order=").append(order).append('\n');
            if (order != null) {
                append(state, "주문", () -> {
                    Order stored = storedOrder();
                    return stored.getStatus() + ", confirmedAt=" + stored.getConfirmedAt();
                });
                append(state, "Outbox 행", () -> outboxEventRepository.findAllByOrderId(order.orderId()).stream()
                    .map(OrderConfirmedOutboxDeliveryE2ETest::describe).toList());
                append(state, "재고", () -> order.productIds().stream().map(id -> id + "=" + stockOf(id)).toList());
                append(state, "PG 생성 요청", flow::pgCreates);
                append(state, "topic record(offset·key)", () -> allRecords().stream()
                    .map(record -> record.partition() + "@" + record.offset() + " key=" + new String(record.key(), StandardCharsets.UTF_8))
                    .toList());
                append(state, "처리 기록", () -> jdbc.queryForList(
                    "select event_id, order_id, processed_at from processed_order_confirmation where order_id = ?", order.orderId()));
                append(state, "구매 활동", () -> jdbc.queryForList(
                    "select product_id, quantity from product_purchase_activity where order_id = ?", order.orderId()));
                append(state, "conflict", () -> jdbc.queryForList(
                    "select record_partition, record_offset, reason from purchase_activity_conflict where order_id = ?", order.orderId()));
            }
            append(state, "계약 깨짐", () -> jdbc.queryForList(
                "select record_partition, record_offset, reason from order_confirmed_contract_violation where record_topic = ?", TOPIC));
            append(state, "consumer group commit", OrderConfirmedOutboxDeliveryE2ETest::committedOffsets);
            state.append("streamer alive=").append(streamer.isAlive()).append(", log=").append(streamer.logFile()).append('\n');
            state.append("----- streamer 로그 끝부분 -----\n").append(streamer.tail()).append('\n');
            System.out.println(state);
        }
    }

    private interface Probe {
        Object read() throws Exception;
    }

    private static void append(StringBuilder state, String label, Probe probe) {
        try {
            state.append(label).append(": ").append(probe.read()).append('\n');
        } catch (Exception e) {
            state.append(label).append(": (읽기 실패 ").append(e).append(")\n");
        }
    }

    private static String describe(OutboxEvent event) {
        return "OutboxEvent{id=" + event.getId() + ", eventId=" + event.getEventId() + ", status=" + event.getStatus()
            + ", attemptCount=" + event.getAttemptCount() + ", nextAttemptAt=" + event.getNextAttemptAt()
            + ", publishedAt=" + event.getPublishedAt() + ", lastError=" + event.getLastError() + "}";
    }

    // ---------------------------------------------------------------- Kafka 읽기

    private static KafkaConsumer<byte[], byte[]> consumer() {
        return new KafkaConsumer<>(Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestContainers.bootstrapServers(),
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class,
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false));
    }

    /** 이 테스트만 쓰는 topic의 record 전부. 지금의 끝 offset까지 읽었는지 확인하고 돌려준다. */
    private static List<ConsumerRecord<byte[], byte[]>> allRecords() {
        List<TopicPartition> partitions = IntStream.range(0, KafkaTestContainers.PARTITIONS)
            .mapToObj(p -> new TopicPartition(TOPIC, p)).toList();
        try (KafkaConsumer<byte[], byte[]> consumer = consumer()) {
            Map<TopicPartition, Long> end = consumer.endOffsets(partitions);
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            List<ConsumerRecord<byte[], byte[]>> records = new ArrayList<>();
            long deadline = System.nanoTime() + WAIT.toNanos();
            while (!reached(consumer, end) && System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(200)).forEach(records::add);
            }
            assertThat(reached(consumer, end)).as("끝 offset %s까지 읽기 (읽은 수 %d)", end, records.size()).isTrue();
            return records;
        }
    }

    private static boolean reached(KafkaConsumer<byte[], byte[]> consumer, Map<TopicPartition, Long> end) {
        return end.entrySet().stream().allMatch(entry -> consumer.position(entry.getKey()) >= entry.getValue());
    }

    private static String eventIdIn(ConsumerRecord<byte[], byte[]> record) throws Exception {
        return MAPPER.readTree(new String(record.value(), StandardCharsets.UTF_8)).get("eventId").asText();
    }

    private static Map<TopicPartition, OffsetAndMetadata> committedOffsets() throws Exception {
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestContainers.bootstrapServers()))) {
            return admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata().get(10, TimeUnit.SECONDS);
        }
    }

    /** consumer group이 이 partition에 commit한 위치(다음에 읽을 offset). 아직 없으면 -1. */
    private static long committedOffset(TopicPartition partition) throws Exception {
        OffsetAndMetadata committed = committedOffsets().get(partition);
        return committed == null ? -1 : committed.offset();
    }

    // ---------------------------------------------------------------- 이음매

    @TestConfiguration
    static class SeamConfig {
        @Bean
        @Primary
        TestClock testClock() {
            return new TestClock();
        }

        @Bean
        @Primary
        SwitchableLedger switchableLedger(@Qualifier("outboxDeliveryLedger") OutboxDeliveryLedger outboxDeliveryLedger) {
            return new SwitchableLedger(outboxDeliveryLedger);
        }
    }
}
