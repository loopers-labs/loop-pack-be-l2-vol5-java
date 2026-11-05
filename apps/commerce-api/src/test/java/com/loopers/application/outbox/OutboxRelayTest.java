package com.loopers.application.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.loopers.application.order.ConfirmPaymentCommand;
import com.loopers.application.order.OrderConfirmedPayload;
import com.loopers.application.order.OrderConfirmedPublisher;
import com.loopers.application.order.OrderConfirmedSendException;
import com.loopers.application.order.OrderFacade;
import com.loopers.application.order.OrderInfo;
import com.loopers.application.order.OrderItemRequest;
import com.loopers.application.order.SentRecord;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.outbox.OutboxEvent;
import com.loopers.domain.outbox.OutboxEventRepository;
import com.loopers.domain.outbox.OutboxStatus;
import com.loopers.domain.payment.PaymentAttemptRepository;
import com.loopers.domain.payment.PaymentAttemptStatus;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.user.UserModel;
import com.loopers.infrastructure.order.KafkaOrderConfirmedPublisher;
import com.loopers.infrastructure.order.OrderEventKafkaConfig;
import com.loopers.infrastructure.order.OrderJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.support.pg.TestPg;
import com.loopers.testcontainers.KafkaTestContainers;
import com.loopers.utils.DatabaseCleanUp;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * relay가 Outbox 행을 실제 broker까지 보내는 장면들(docs/week7/decisions.md D-2, D-3, D-4, D-11, D-15, INV-702~705).
 * 실제 MySQL·Kafka(테스트 컨테이너)와 테스트 PG를 쓰고, 행은 실제 확정 경로(결제 확인 → SUCCESS 반영)로만 만든다.
 * 상태 컬럼을 SQL로 쓰거나 PENDING으로 되돌리지 않는다.
 *
 * <p>이음매는 셋이다. 모두 운영 코드에 테스트 분기를 두지 않고 빈을 바꿔 끼운다(6주차 D-16의 SwitchablePublisher와 같은 방식).
 * - 시계: 실제 현재 시각에서 시작해 앞으로만 넘기는 Clock(D-11, D-16). created_at은 BaseEntity가 JVM 시각으로 채우므로,
 *   시계가 실제 시각보다 뒤에 있으면 24시간 판정이 어긋난다.
 * - 전송 포트 래퍼: 진짜 Kafka 구현에 넘기거나(PASS) 실패를 던진다(FAIL).
 * - 결과 기록 빈 래퍼: 진짜 빈에 넘기거나(PASS) PUBLISHED 저장 호출에서 받은 broker 위치만 적고 멈춘다(HALT) — broker 확인
 *   뒤 PUBLISHED 저장 전에 서버가 죽은 것과 같다(D-11 이유 2).
 *
 * <p>격리: 장면마다 DB를 비우고 시계를 그때의 실제 현재 시각에서 다시 시작한다. 한 장면 안에서 시계는 앞으로만 간다 — 앞 장면이
 * 24시간을 넘긴 시계를 다음 장면이 이어받으면, 방금 만든 행이 첫 실패에서 BLOCKED가 된다. DB를 비우면 주문 id가 1부터 다시
 * 쓰여 eventId·key가 앞 장면과 같아지므로, Kafka는 장면 시작 때의 끝 offset 뒤의 record만 읽는다. topic은 이 클래스만 쓴다.
 */
@SpringBootTest
@Import(OutboxRelayTest.SeamConfig.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OutboxRelayTest {

    private static final String TOPIC = "order-events-relay-" + UUID.randomUUID();
    private static final Duration WAIT = Duration.ofSeconds(20);
    private static final long PRICE = 1_000L;
    private static final int INITIAL_STOCK = 10;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String INJECTED_FAILURE = "주입된 전송 실패";

    @DynamicPropertySource
    static void topic(DynamicPropertyRegistry registry) {
        KafkaTestContainers.createTopic(TOPIC);
        registry.add("events.order-confirmed.topic", () -> TOPIC);
    }

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private OutboxResume outboxResume;

    @Autowired
    private TestClock clock;

    @Autowired
    private SwitchablePublisher publisher;

    @Autowired
    private SwitchableLedger ledger;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    @Qualifier(OrderEventKafkaConfig.PRODUCER_FACTORY)
    private ProducerFactory<String, String> orderEventProducerFactory;

    @Value("${outbox.relay.batch-size}")
    private int batchSize;

    @Value("${outbox.relay.lease-margin}")
    private Duration leaseMargin;

    @Autowired
    private OrderFacade orderFacade;

    @Autowired
    private PaymentAttemptRepository paymentAttemptRepository;

    @Autowired
    private ProductJpaRepository productJpaRepository;

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Autowired
    private OrderJpaRepository orderJpaRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private final WireMockServer pg = TestPg.server();
    private Map<TopicPartition, Long> startOffsets;

    @BeforeEach
    void setUp() {
        databaseCleanUp.truncateAllTables();
        clock.restartAtRealNow();
        publisher.reset();
        ledger.reset();
        startOffsets = endOffsets();
    }

    @AfterAll
    void cleanUp() {
        databaseCleanUp.truncateAllTables();
    }

    // ---------------------------------------------------------------- fixtures

    private record Fixture(Long userId, Long orderId, Map<Long, Integer> quantityByProductId) {
        String providerOrderId() {
            return String.format("LP-ORD-%010d", orderId);
        }

        String transactionKey() {
            return TestPg.transactionKeyOf(providerOrderId());
        }

        long amount() {
            return quantityByProductId.values().stream().mapToLong(quantity -> quantity * PRICE).sum();
        }

        String eventId() {
            return OrderConfirmedPayload.eventIdOf(orderId);
        }

        String messageKey() {
            return String.valueOf(orderId);
        }
    }

    /** 결제 확인(PG 202 처리중) → 같은 거래 조회 SUCCESS 반영으로 확정한다. 확정 TX가 PENDING Outbox 행을 함께 저장한다(INV-701). */
    private Fixture confirmed() {
        Fixture fixture = draft();
        orderFacade.confirmOrder(fixture.orderId(), fixture.userId(),
            new ConfirmPaymentCommand("key-" + fixture.orderId(), "TEST", "0000-0000-0000-0000"));
        pg.stubFor(get(urlEqualTo(TestPg.PAYMENTS_PATH + "/" + fixture.transactionKey()))
            .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                .withBody("{\"transactionKey\":\"" + fixture.transactionKey() + "\",\"orderId\":\"" + fixture.providerOrderId()
                    + "\",\"amount\":" + fixture.amount() + ",\"status\":\"SUCCESS\"}")));
        OrderInfo response = orderFacade.reconcilePayment(fixture.orderId(), fixture.userId());
        assertThat(response.status()).as("확정 응답").isEqualTo(OrderStatus.CONFIRMED);
        assertThat(row(fixture).getStatus()).isEqualTo(OutboxStatus.PENDING);
        return fixture;
    }

    private Fixture draft() {
        Long userId = userJpaRepository.save(new UserModel()).getId();
        Map<Long, Integer> quantities = new HashMap<>();
        List<OrderItemRequest> items = new ArrayList<>();
        for (int quantity : new int[] {2, 1}) {
            Long productId = productJpaRepository.save(new ProductModel("상품", PRICE, 1L, INITIAL_STOCK)).getId();
            quantities.put(productId, quantity);
            items.add(new OrderItemRequest(productId, quantity));
        }
        Long orderId = orderFacade.createOrder(userId, items).id();
        return new Fixture(userId, orderId, quantities);
    }

    private OutboxEvent row(Fixture fixture) {
        List<OutboxEvent> rows = outboxEventRepository.findAllByOrderId(fixture.orderId());
        assertThat(rows).as("주문 %d의 Outbox 행", fixture.orderId()).hasSize(1);
        return rows.get(0);
    }

    private Order order(Fixture fixture) {
        return orderJpaRepository.findById(fixture.orderId()).orElseThrow();
    }

    private void assertConfirmedAndReserved(Fixture fixture) {
        assertThat(order(fixture).getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(paymentAttemptRepository.findByOrderId(fixture.orderId()).orElseThrow().getStatus())
            .isEqualTo(PaymentAttemptStatus.SUCCESS);
        fixture.quantityByProductId().forEach((productId, quantity) ->
            assertThat(productJpaRepository.findById(productId).orElseThrow().getRemainingStock())
                .isEqualTo(INITIAL_STOCK - quantity));
    }

    private ZonedDateTime now() {
        return ZonedDateTime.now(clock);
    }

    /** 실제 producer 설정에서 읽은 최대 전송 시간. 구현(KafkaOrderConfirmedPublisher)과 독립된 기대값이다. */
    private Duration producerMaxSendTime() {
        Map<String, Object> config = orderEventProducerFactory.getConfigurationProperties();
        long maxBlock = Long.parseLong(String.valueOf(config.get(ProducerConfig.MAX_BLOCK_MS_CONFIG)));
        long deliveryTimeout = Long.parseLong(String.valueOf(config.getOrDefault(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 120_000)));
        return Duration.ofMillis(maxBlock + deliveryTimeout);
    }

    private static String describe(OutboxEvent event) {
        return "OutboxEvent{id=" + event.getId() + ", eventId=" + event.getEventId() + ", status=" + event.getStatus()
            + ", attemptCount=" + event.getAttemptCount() + ", nextAttemptAt=" + event.getNextAttemptAt()
            + ", publishedAt=" + event.getPublishedAt() + ", createdAt=" + event.getCreatedAt()
            + ", lastError=" + event.getLastError() + "}";
    }

    // ---------------------------------------------------------------- Kafka 읽기

    private static KafkaConsumer<byte[], byte[]> consumer() {
        return new KafkaConsumer<>(Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestContainers.bootstrapServers(),
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class,
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false));
    }

    private static List<TopicPartition> partitions() {
        return IntStream.range(0, KafkaTestContainers.PARTITIONS).mapToObj(p -> new TopicPartition(TOPIC, p)).toList();
    }

    private static Map<TopicPartition, Long> endOffsets() {
        try (KafkaConsumer<byte[], byte[]> consumer = consumer()) {
            return new HashMap<>(consumer.endOffsets(partitions()));
        }
    }

    /** 이 장면이 시작된 뒤 topic에 실린 record 전부. 지금의 끝 offset까지 읽었는지 확인하고 돌려준다. */
    private List<ConsumerRecord<byte[], byte[]>> recordsSinceStart() {
        try (KafkaConsumer<byte[], byte[]> consumer = consumer()) {
            List<TopicPartition> partitions = partitions();
            Map<TopicPartition, Long> end = consumer.endOffsets(partitions);
            consumer.assign(partitions);
            partitions.forEach(partition -> consumer.seek(partition, startOffsets.get(partition)));
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

    private List<ConsumerRecord<byte[], byte[]>> recordsOf(Fixture fixture) {
        byte[] key = fixture.messageKey().getBytes(StandardCharsets.UTF_8);
        return recordsSinceStart().stream().filter(record -> Arrays.equals(record.key(), key)).toList();
    }

    private static ConsumerRecord<byte[], byte[]> readAt(SentRecord sent) {
        try (KafkaConsumer<byte[], byte[]> consumer = consumer()) {
            TopicPartition partition = new TopicPartition(sent.topic(), sent.partition());
            consumer.assign(List.of(partition));
            consumer.seek(partition, sent.offset());
            List<ConsumerRecord<byte[], byte[]>> records = new ArrayList<>();
            long deadline = System.nanoTime() + WAIT.toNanos();
            while (records.isEmpty() && System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(200)).forEach(records::add);
            }
            assertThat(records).as("broker가 준 위치 %s에서 record 읽기", sent).isNotEmpty();
            return records.get(0);
        }
    }

    private static String eventIdIn(ConsumerRecord<byte[], byte[]> record) throws Exception {
        return MAPPER.readTree(new String(record.value(), StandardCharsets.UTF_8)).get("eventId").asText();
    }

    // ---------------------------------------------------------------- scenes

    @DisplayName("정상: relay 한 회차가 행을 보내면 PUBLISHED·published_at·시도 1회가 남고, broker에서 다시 읽은 record의 key는 "
        + "message_key, value는 저장된 payload와 바이트까지 같다. (INV-702 ②, INV-703)")
    @Test
    void success_publishesStoredPayloadUnchanged() {
        // arrange
        Fixture fixture = confirmed();
        clock.catchUpToRealNow();

        // act
        OutboxRelayRound round = relay.runOnce();

        // assert
        OutboxEvent row = row(fixture);
        System.out.println("[relay 정상] " + round + " / " + describe(row));
        List<SentRecord> sent = ledger.publishedFor(row.getId());
        assertThat(sent).hasSize(1);
        ConsumerRecord<byte[], byte[]> record = readAt(sent.get(0));
        assertAll(
            () -> assertThat(round).isEqualTo(new OutboxRelayRound(false, 1, 1, 1, 0, 0)),
            () -> assertThat(row.getStatus()).isEqualTo(OutboxStatus.PUBLISHED),
            () -> assertThat(row.getPublishedAt()).isNotNull(),
            () -> assertThat(row.getPublishedAt().toInstant()).isEqualTo(clock.instant()),
            () -> assertThat(row.getAttemptCount()).isEqualTo(1),
            () -> assertThat(row.getLastError()).isNull(),
            () -> assertThat(sent.get(0).topic()).isEqualTo(TOPIC),
            () -> assertThat(record.key()).isEqualTo(row.getMessageKey().getBytes(StandardCharsets.UTF_8)),
            () -> assertThat(record.value()).isEqualTo(row.getPayload().getBytes(StandardCharsets.UTF_8)),
            () -> assertThat(recordsOf(fixture)).hasSize(1),
            () -> assertConfirmedAndReserved(fixture)
        );
    }

    /**
     * 6주차 sendFailure_doesNotChangeOrderOrResponse("발행이 실패해도 주문은 CONFIRMED, 고객은 성공")의 보장이 여기로 왔다.
     * 6주차에는 실패가 미발행 Counter와 ERROR 로그로만 남았다. 이제는 행의 last_error와 다음 시도 시각으로 남는다(D-9).
     */
    @DisplayName("전송 실패: 행은 PENDING으로 남고 last_error(payload 없음)와 다음 시도 = 지금 + 1초가 적힌다. 그 전에는 다시 집지 않고, "
        + "시간이 지나면 다시 집어 다음 백오프는 2초, … 5분에서 멈춘다. 주문은 CONFIRMED 그대로다. (D-2, D-3, W6 sendFailure)")
    @Test
    void sendFailure_keepsPendingWithBackoff_andLeavesOrderUnchanged() {
        // arrange
        Fixture fixture = confirmed();
        Order before = order(fixture);
        publisher.mode(PublisherMode.FAIL);
        clock.catchUpToRealNow();

        // act — 첫 실패
        OutboxRelayRound first = relay.runOnce();

        // assert — 지금 + 1초, 그 전에는 집지 않는다
        OutboxEvent row = row(fixture);
        System.out.println("[relay 전송 실패 1회] " + first + " / " + describe(row));
        ZonedDateTime failedAt = now();
        assertAll(
            () -> assertThat(first).isEqualTo(new OutboxRelayRound(false, 1, 1, 0, 1, 0)),
            () -> assertThat(row.getStatus()).isEqualTo(OutboxStatus.PENDING),
            () -> assertThat(row.getAttemptCount()).isEqualTo(1),
            () -> assertThat(row.getPublishedAt()).isNull(),
            () -> assertThat(row.getNextAttemptAt().toInstant()).isEqualTo(failedAt.plusSeconds(1).toInstant()),
            () -> assertThat(row.getLastError()).contains(OrderConfirmedSendException.class.getSimpleName(), INJECTED_FAILURE),
            () -> assertThat(row.getLastError()).doesNotContain(row.getPayload(), "\"items\"", "\"confirmedAt\"")
        );
        assertThat(relay.runOnce().due()).as("같은 시각에는 고르지 않는다").isZero();
        clock.advance(Duration.ofMillis(999));
        assertThat(relay.runOnce().due()).as("1초 전에는 고르지 않는다").isZero();

        // act — 다음 시도들: 2초, 4초 … 256초, 그리고 5분에서 멈춤
        List<Long> backoffSeconds = new ArrayList<>();
        clock.advance(Duration.ofMillis(1));
        for (int attempt = 2; attempt <= 11; attempt++) {
            OutboxRelayRound round = relay.runOnce();
            OutboxEvent current = row(fixture);
            assertThat(round.picked()).as("시도 %d: %s", attempt, describe(current)).isEqualTo(1);
            assertThat(current.getAttemptCount()).isEqualTo(attempt);
            Duration backoff = Duration.between(now(), current.getNextAttemptAt());
            backoffSeconds.add(backoff.toSeconds());
            clock.advance(backoff);
        }

        // assert
        Order after = order(fixture);
        assertAll(
            () -> assertThat(backoffSeconds).containsExactly(2L, 4L, 8L, 16L, 32L, 64L, 128L, 256L, 300L, 300L),
            () -> assertThat(row(fixture).getStatus()).isEqualTo(OutboxStatus.PENDING),
            () -> assertThat(publisher.callsFor(fixture.messageKey())).isEqualTo(11),
            () -> assertConfirmedAndReserved(fixture),
            () -> assertThat(after.getConfirmedAt().toInstant()).isEqualTo(before.getConfirmedAt().toInstant()),
            () -> assertThat(after.getStatus()).isEqualTo(before.getStatus()),
            () -> assertThat(recordsOf(fixture)).isEmpty()
        );
    }

    @DisplayName("임대: 집힌 행은 임대가 끝나기 전에는 다른 회차가 고르지도 집지도 못하고(조건부 UPDATE 0줄), 임대가 지나면 집힌다. "
        + "임대는 실제 producer 설정의 max.block + delivery.timeout 이상이다. (INV-704, D-2, D-4)")
    @Test
    void lease_blocksOtherRoundsUntilItExpires() {
        // arrange — 한 회차가 집어 둔 채 아직 보내는 중인 상태(TX-1만 commit됨)
        Fixture fixture = confirmed();
        clock.catchUpToRealNow();
        Long id = row(fixture).getId();
        Duration lease = relay.lease();
        ZonedDateTime claimedAt = now();
        Optional<ClaimedOutbox> claimed = ledger.claim(id, claimedAt, claimedAt.plus(lease));

        // act & assert — 임대 중
        assertThat(claimed).isPresent();
        assertThat(claimed.get().attemptCount()).isEqualTo(1);
        assertThat(row(fixture).getNextAttemptAt().toInstant()).isEqualTo(claimedAt.plus(lease).toInstant());
        assertThat(relay.runOnce().due()).as("임대 중에는 고르지 않는다").isZero();
        assertThat(outboxEventRepository.claim(id, now(), now().plus(lease))).as("집은 직후 조건부 UPDATE").isZero();
        clock.advance(lease.minusNanos(1_000));
        assertThat(relay.runOnce().due()).as("임대 끝 1µs 전").isZero();
        assertThat(outboxEventRepository.claim(id, now(), now().plus(lease))).as("임대 끝 1µs 전 조건부 UPDATE").isZero();
        assertThat(publisher.callsFor(fixture.messageKey())).isZero();

        // act — 임대가 지남
        clock.advance(Duration.ofNanos(1_000));
        OutboxRelayRound round = relay.runOnce();

        // assert
        OutboxEvent row = row(fixture);
        System.out.println("[relay 임대] lease=" + lease + ", producerMaxSendTime=" + producerMaxSendTime() + " / " + describe(row));
        assertAll(
            () -> assertThat(lease).isGreaterThanOrEqualTo(producerMaxSendTime()),
            () -> assertThat(lease).isEqualTo(producerMaxSendTime().plus(leaseMargin)),
            () -> assertThat(round).isEqualTo(new OutboxRelayRound(false, 1, 1, 1, 0, 0)),
            () -> assertThat(row.getStatus()).isEqualTo(OutboxStatus.PUBLISHED),
            () -> assertThat(row.getAttemptCount()).isEqualTo(2),
            () -> assertThat(recordsOf(fixture)).hasSize(1)
        );
    }

    @DisplayName("24시간: created_at + 24시간 직전의 실패는 다시 시도하고, 그 뒤의 실패는 BLOCKED로 뺀다. BLOCKED는 회차가 고르지 않는다. "
        + "재개하면 같은 행·같은 eventId·같은 payload로 나가 PUBLISHED가 된다. (D-3, INV-705)")
    @Test
    void blockedAfterTwentyFourHours_thenResumedOnSameRow() throws Exception {
        // arrange
        Fixture fixture = confirmed();
        OutboxEvent original = row(fixture);
        publisher.mode(PublisherMode.FAIL);
        clock.catchUpToRealNow();
        relay.runOnce();

        // act — 24시간 1초 전의 실패는 아직 다시 시도
        clock.advanceTo(original.getCreatedAt().plusHours(24).minusSeconds(1));
        OutboxRelayRound beforeDeadline = relay.runOnce();
        OutboxEvent stillPending = row(fixture);

        // act — 24시간이 지난 뒤의 실패는 BLOCKED
        clock.advanceTo(stillPending.getNextAttemptAt());
        OutboxRelayRound afterDeadline = relay.runOnce();
        OutboxEvent blocked = row(fixture);
        System.out.println("[relay 24시간] " + afterDeadline + " / " + describe(blocked));

        // assert
        assertAll(
            () -> assertThat(beforeDeadline).isEqualTo(new OutboxRelayRound(false, 1, 1, 0, 1, 0)),
            () -> assertThat(stillPending.getStatus()).isEqualTo(OutboxStatus.PENDING),
            () -> assertThat(afterDeadline).isEqualTo(new OutboxRelayRound(false, 1, 1, 0, 1, 1)),
            () -> assertThat(blocked.getStatus()).isEqualTo(OutboxStatus.BLOCKED),
            () -> assertThat(blocked.getAttemptCount()).isEqualTo(3),
            () -> assertThat(blocked.getLastError()).contains(INJECTED_FAILURE)
        );

        // act & assert — BLOCKED는 더 고르지 않는다. 지우지도 않는다
        clock.advance(Duration.ofHours(1));
        assertThat(relay.runOnce().due()).isZero();
        assertThat(row(fixture).getStatus()).isEqualTo(OutboxStatus.BLOCKED);
        assertThat(publisher.callsFor(fixture.messageKey())).isEqualTo(3);

        // act — broker가 돌아온 뒤 재개
        publisher.mode(PublisherMode.PASS);
        OutboxRelayRound resumed = outboxResume.resume(fixture.eventId());

        // assert
        OutboxEvent published = row(fixture);
        List<ConsumerRecord<byte[], byte[]>> records = recordsOf(fixture);
        assertAll(
            () -> assertThat(resumed).isEqualTo(new OutboxRelayRound(false, 1, 1, 1, 0, 0)),
            () -> assertThat(published.getId()).isEqualTo(original.getId()),
            () -> assertThat(published.getEventId()).isEqualTo(original.getEventId()),
            () -> assertThat(published.getPayload()).isEqualTo(original.getPayload()),
            () -> assertThat(published.getStatus()).isEqualTo(OutboxStatus.PUBLISHED),
            () -> assertThat(published.getAttemptCount()).isEqualTo(4),
            () -> assertThat(outboxEventRepository.findAllByOrderId(fixture.orderId())).hasSize(1),
            () -> assertThat(records).hasSize(1),
            () -> assertThat(eventIdIn(records.get(0))).isEqualTo(original.getEventId()),
            () -> assertThat(records.get(0).value()).isEqualTo(original.getPayload().getBytes(StandardCharsets.UTF_8))
        );
    }

    @DisplayName("broker 회복: broker가 안 받는 동안 실패한 행들은 PENDING으로 남고, broker가 돌아온 뒤 백오프가 지나면 모두 PUBLISHED가 된다. "
        + "(D-2, D-3 — 누락 없는 at-least-once)")
    @Test
    void brokerRecovery_publishesAllPendingRows() {
        // arrange
        List<Fixture> fixtures = List.of(confirmed(), confirmed(), confirmed());
        publisher.mode(PublisherMode.FAIL);
        clock.catchUpToRealNow();
        OutboxRelayRound failedRound = relay.runOnce();
        fixtures.forEach(fixture -> assertThat(row(fixture).getStatus()).isEqualTo(OutboxStatus.PENDING));

        // act
        publisher.mode(PublisherMode.PASS);
        assertThat(relay.runOnce().due()).as("백오프 전").isZero();
        clock.advance(Duration.ofSeconds(1));
        OutboxRelayRound recovered = relay.runOnce();

        // assert
        System.out.println("[relay broker 회복] failed=" + failedRound + ", recovered=" + recovered);
        assertAll(
            () -> assertThat(failedRound).isEqualTo(new OutboxRelayRound(false, 3, 3, 0, 3, 0)),
            () -> assertThat(recovered).isEqualTo(new OutboxRelayRound(false, 3, 3, 3, 0, 0)),
            () -> fixtures.forEach(fixture -> {
                OutboxEvent row = row(fixture);
                assertThat(row.getStatus()).as(describe(row)).isEqualTo(OutboxStatus.PUBLISHED);
                assertThat(row.getAttemptCount()).isEqualTo(2);
                assertThat(recordsOf(fixture)).hasSize(1);
            })
        );
    }

    /**
     * 6주차 haltBeforeSend_leavesConfirmedOrderWithoutRecord("commit 뒤 send 직전에 멈추면 기록이 사라진다")의 자리다. 7주차에는
     * 멈춘 자리가 어디든 행이 남는다. 가장 까다로운 자리 — broker는 받았는데 PUBLISHED를 저장하기 전 — 에서 멈춰도, 새로 뜬
     * relay가 임대가 지난 뒤 같은 eventId로 다시 보낸다(과제: "broker 확인 뒤 PUBLISHED 저장 전 중단").
     */
    @DisplayName("broker 확인 뒤 PUBLISHED 저장 전 중단: broker에는 record가 하나 있고 행은 PENDING으로 남는다. 새 relay가 임대가 지난 뒤 "
        + "다시 보내 topic에는 같은 eventId가 다른 offset에 두 번 실리고 행은 PUBLISHED가 된다. (D-2, D-11, INV-703, at-least-once)")
    @Test
    void haltAfterBrokerAck_beforePublished_isResentByNewRelay() throws Exception {
        // arrange
        Fixture fixture = confirmed();
        ledger.mode(SwitchableLedger.Mode.HALT_ON_MARK_PUBLISHED);
        clock.catchUpToRealNow();
        ZonedDateTime claimedAt = now();

        // act — 첫 relay: broker 확인까지 진짜로 끝나고, PUBLISHED 저장 호출에서 멈춘다
        relay.runOnce();

        // assert — broker에 1건, 행은 PENDING(임대 중)
        assertThat(ledger.halted()).hasSize(1);
        SentRecord firstSent = ledger.halted().get(0);
        ConsumerRecord<byte[], byte[]> firstRecord = readAt(firstSent);
        OutboxEvent halted = row(fixture);
        System.out.println("[relay 중단] firstSent=" + firstSent + " / " + describe(halted));
        assertAll(
            () -> assertThat(firstRecord.value()).isEqualTo(halted.getPayload().getBytes(StandardCharsets.UTF_8)),
            () -> assertThat(recordsOf(fixture)).hasSize(1),
            () -> assertThat(halted.getStatus()).isEqualTo(OutboxStatus.PENDING),
            () -> assertThat(halted.getPublishedAt()).isNull(),
            () -> assertThat(halted.getAttemptCount()).isEqualTo(1),
            () -> assertThat(halted.getNextAttemptAt().toInstant()).isEqualTo(claimedAt.plus(relay.lease()).toInstant()),
            () -> assertConfirmedAndReserved(fixture)
        );

        // act — 재시작: 같은 빈·DB·Kafka로 새 relay를 만든다. 임대 전에는 고르지 않고, 지나면 다시 보낸다
        ledger.mode(SwitchableLedger.Mode.PASS);
        OutboxRelay restarted = new OutboxRelay(outboxEventRepository, ledger, publisher, clock, batchSize, leaseMargin);
        assertThat(restarted.runOnce().due()).as("임대 중").isZero();
        clock.advanceTo(halted.getNextAttemptAt());
        OutboxRelayRound resent = restarted.runOnce();

        // assert
        OutboxEvent published = row(fixture);
        List<ConsumerRecord<byte[], byte[]>> records = recordsOf(fixture);
        System.out.println("[relay 재발행] " + resent + " / " + describe(published) + " / offsets="
            + records.stream().map(ConsumerRecord::offset).toList());
        assertAll(
            () -> assertThat(resent).isEqualTo(new OutboxRelayRound(false, 1, 1, 1, 0, 0)),
            () -> assertThat(published.getStatus()).isEqualTo(OutboxStatus.PUBLISHED),
            () -> assertThat(published.getAttemptCount()).isEqualTo(2),
            () -> assertThat(records).hasSize(2),
            () -> assertThat(eventIdIn(records.get(0))).isEqualTo(fixture.eventId()),
            () -> assertThat(eventIdIn(records.get(1))).isEqualTo(fixture.eventId()),
            () -> assertThat(records.get(1).value()).isEqualTo(records.get(0).value()),
            () -> assertThat(records.get(1).partition()).isEqualTo(records.get(0).partition()),
            () -> assertThat(records.get(1).offset()).isNotEqualTo(records.get(0).offset()),
            () -> assertThat(records.get(0).offset()).isEqualTo(firstSent.offset()),
            () -> assertThat(ledger.publishedFor(published.getId())).extracting(SentRecord::offset)
                .containsExactly(records.get(1).offset())
        );
    }

    @DisplayName("commit 뒤 relay 전에 서버가 죽음: 확정된 주문의 행은 PENDING이고 topic에는 그 record가 없다. relay 한 회차가 보낸다. "
        + "(6주차 누락 구간 W6 D-4가 7주차에는 행으로 남는다, INV-701)")
    @Test
    void committedButNotRelayed_isPublishedByNextRound() {
        // arrange — 확정 TX만 commit되고 relay는 아직 돌지 않았다
        Fixture fixture = confirmed();
        assertConfirmedAndReserved(fixture);
        assertThat(row(fixture).getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(recordsOf(fixture)).isEmpty();

        // act
        clock.catchUpToRealNow();
        OutboxRelayRound round = relay.runOnce();

        // assert
        assertAll(
            () -> assertThat(round.sent()).isEqualTo(1),
            () -> assertThat(row(fixture).getStatus()).isEqualTo(OutboxStatus.PUBLISHED),
            () -> assertThat(recordsOf(fixture)).hasSize(1)
        );
    }

    @DisplayName("작은 묶음·안정된 순서: 보낼 차례인 행이 12건이면 한 회차는 id 순서로 10건을 차례로 보내고, 다음 회차가 나머지 2건을 보낸다. (D-15)")
    @Test
    void batchOfTenInIdOrder() {
        // arrange
        List<Fixture> fixtures = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            fixtures.add(confirmed());
        }
        List<Long> ids = fixtures.stream().map(fixture -> row(fixture).getId()).sorted().toList();
        clock.catchUpToRealNow();

        // act
        OutboxRelayRound first = relay.runOnce();
        List<Long> firstOrder = ledger.publishedIds();
        OutboxRelayRound second = relay.runOnce();

        // assert
        System.out.println("[relay 묶음] first=" + first + ", second=" + second + ", order=" + ledger.publishedIds());
        assertAll(
            () -> assertThat(batchSize).isEqualTo(10),
            () -> assertThat(first).isEqualTo(new OutboxRelayRound(false, 10, 10, 10, 0, 0)),
            () -> assertThat(firstOrder).containsExactlyElementsOf(ids.subList(0, 10)),
            () -> assertThat(second).isEqualTo(new OutboxRelayRound(false, 2, 2, 2, 0, 0)),
            () -> assertThat(ledger.publishedIds()).containsExactlyElementsOf(ids),
            () -> assertThat(relay.runOnce().due()).isZero(),
            () -> assertThat(recordsSinceStart()).hasSize(12)
        );
    }

    // ---------------------------------------------------------------- 이음매

    enum PublisherMode { PASS, FAIL }

    @TestConfiguration
    static class SeamConfig {
        @Bean
        @Primary
        TestClock testClock() {
            return new TestClock();
        }

        @Bean
        @Primary
        SwitchablePublisher switchablePublisher(KafkaOrderConfirmedPublisher kafkaOrderConfirmedPublisher) {
            return new SwitchablePublisher(kafkaOrderConfirmedPublisher);
        }

        @Bean
        @Primary
        SwitchableLedger switchableLedger(@Qualifier("outboxDeliveryLedger") OutboxDeliveryLedger outboxDeliveryLedger) {
            return new SwitchableLedger(outboxDeliveryLedger);
        }
    }

    /** 진짜 Kafka 구현을 감싼다. PASS면 그대로 넘기고, FAIL이면 broker가 받지 않은 것처럼 전송 예외를 던진다. 호출을 key별로 센다. */
    static class SwitchablePublisher implements OrderConfirmedPublisher {

        private final OrderConfirmedPublisher delegate;
        private final Map<String, Integer> calls = new ConcurrentHashMap<>();
        private volatile PublisherMode mode = PublisherMode.PASS;

        SwitchablePublisher(OrderConfirmedPublisher delegate) {
            this.delegate = delegate;
        }

        void reset() {
            mode = PublisherMode.PASS;
            calls.clear();
        }

        void mode(PublisherMode mode) {
            this.mode = mode;
        }

        int callsFor(String messageKey) {
            return calls.getOrDefault(messageKey, 0);
        }

        @Override
        public SentRecord send(String messageKey, String payload) {
            calls.merge(messageKey, 1, Integer::sum);
            if (mode == PublisherMode.FAIL) {
                throw new OrderConfirmedSendException(INJECTED_FAILURE + ": key=" + messageKey, null);
            }
            return delegate.send(messageKey, payload);
        }

        @Override
        public Duration maxSendTime() {
            return delegate.maxSendTime();
        }
    }
}
