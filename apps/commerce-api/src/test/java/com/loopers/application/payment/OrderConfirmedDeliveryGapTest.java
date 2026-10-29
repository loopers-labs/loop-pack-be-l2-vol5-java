package com.loopers.application.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.order.OrderCommand;
import com.loopers.application.order.OrderConfirmInfo;
import com.loopers.application.order.OrderFacade;
import com.loopers.domain.order.Order;
import com.loopers.domain.payment.PaymentAttempt;
import com.loopers.domain.product.Price;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.StockReservation;
import com.loopers.domain.user.User;
import com.loopers.infrastructure.order.OrderJpaRepository;
import com.loopers.infrastructure.payment.FakeOrderConfirmedPublisher;
import com.loopers.infrastructure.payment.FakeOrderConfirmedPublisherConfig;
import com.loopers.infrastructure.payment.FakePaymentGateway;
import com.loopers.infrastructure.payment.FakePaymentGatewayConfig;
import com.loopers.infrastructure.payment.KafkaOrderConfirmedPublisher;
import com.loopers.infrastructure.payment.PaymentAttemptJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.infrastructure.product.StockReservationJpaRepository;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.testcontainers.KafkaTestContainersConfig;
import com.loopers.utils.DatabaseCleanUp;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * 과제 2절 누락 구간 실험 (📄 p.3~4). 같은 W6 직접 발행 경로로 두 주문을 확정한다.
 * - 사례 1(누락): 확정 TX commit 뒤, 전송 호출 직전 hook 에서 중단한다. 주문·예약은 남고 broker record 는 없다.
 * - 사례 2(정상): hook 을 통과해 실제 broker 에 발행한다. 이 record 의 bytes 를 commerce-streamer 의 재생 테스트가 그대로 소비한다.
 * 실패 주입은 통제된 hook 이며 OS 강제 종료가 아니다. DB 와 broker 가 원자적으로 묶이지 않았다는 증거이고 W7 Outbox 의 출발점이다.
 * 관찰값은 build/delivery-gap/observation.json 에 남긴다 (카드 값·비밀정보 없음).
 */
@SpringBootTest(properties = "order-events.topic=" + OrderConfirmedDeliveryGapTest.TOPIC)
@Import({FakePaymentGatewayConfig.class, FakeOrderConfirmedPublisherConfig.class})
class OrderConfirmedDeliveryGapTest {

    static final String TOPIC = "order-events-OrderConfirmedDeliveryGapTest";
    private static final Path OBSERVATION = Path.of("build", "delivery-gap", "observation.json");
    private static final String CARD_TYPE = "TEST";
    private static final String CARD_NO = "0000-0000-0000-0000";
    /** 고정 clock. 두 주문 모두 이 시각(밀리초 절단값 2026-09-07T01:00:00.123Z)으로 확정된다. */
    private static final Instant NOW = Instant.parse("2026-09-07T01:00:00.123456789Z");
    private static final Instant CONFIRMED_AT = Instant.parse("2026-09-07T01:00:00.123Z");

    static {
        KafkaTestContainersConfig.createTopic(TOPIC, 1);
    }

    @MockitoBean
    private Clock clock;

    @Autowired
    private PaymentFacade paymentFacade;
    @Autowired
    private OrderFacade orderFacade;
    @Autowired
    private FakePaymentGateway fakePaymentGateway;
    @Autowired
    private FakeOrderConfirmedPublisher fakePublisher;
    @Autowired
    private KafkaOrderConfirmedPublisher kafkaPublisher;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private UserJpaRepository userJpaRepository;
    @Autowired
    private ProductJpaRepository productJpaRepository;
    @Autowired
    private OrderJpaRepository orderJpaRepository;
    @Autowired
    private PaymentAttemptJpaRepository paymentAttemptJpaRepository;
    @Autowired
    private StockReservationJpaRepository stockReservationJpaRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @BeforeEach
    void setUp() {
        fakePaymentGateway.reset();
        fakePublisher.reset();
        given(clock.instant()).willReturn(NOW);
        given(clock.getZone()).willReturn(ZoneId.of("Asia/Seoul"));
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("사례 1 은 commit 뒤 send 직전 중단으로 주문 CONFIRMED·예약 CONSUMED 만 남고 broker record 가 없다. 사례 2 는 같은 경로로 실제 broker 에 발행된다.")
    @Test
    void recordsMissingAndDeliveredCases() throws IOException {
        // arrange - 고정 품목: 상품 A × 2, 상품 B × 1
        Long userId = userJpaRepository.save(new User()).getId();
        Long productA = saveProduct("상품 A", 1000L, 10);
        Long productB = saveProduct("상품 B", 2000L, 10);
        List<Map<String, Object>> observations = new ArrayList<>();

        // ---- 사례 1: commit 뒤, 전송 호출 직전 hook 에서 중단 ----
        Long missingOrderId = createOrder(userId, productA, productB);
        PaymentAttempt missingAttempt = startPayment(userId, missingOrderId, "gap-1");
        fakePaymentGateway.markSuccess(missingAttempt.getTransactionKey());
        fakePublisher.beforeSend(event -> {
            throw new IllegalStateException("실험: 확정 commit 뒤 send 직전에 중단");
        });
        fakePublisher.delegateTo(kafkaPublisher);

        OrderConfirmInfo missingInfo = paymentFacade.applyCallback(missingAttempt.getProviderOrderId(), missingAttempt.getTransactionKey());

        // assert 1 - 주문·예약·시도는 확정된 채 남고, 응답도 저장 결과(settled)다. 발행 실패가 주문을 되돌리지 않는다
        assertThat(missingInfo.settled()).isTrue();
        Order missingOrder = orderOf(missingOrderId);
        assertThat(missingOrder.getStatus()).isEqualTo(Order.OrderStatus.CONFIRMED);
        assertThat(missingOrder.getConfirmedAt().toInstant()).isEqualTo(CONFIRMED_AT);
        assertThat(reservationStatusesOf(missingOrderId)).containsOnly(StockReservation.Status.CONSUMED);
        assertThat(attemptOf(missingOrderId).getStatus()).isEqualTo(PaymentAttempt.Status.SUCCESS);
        assertThat(fakePublisher.publishCallCount()).isEqualTo(1);
        assertThat(fakePublisher.published()).isEmpty();
        String missingEventId = "order-confirmed:v1:" + missingOrderId;
        assertThat(readAll()).noneMatch(record -> String.valueOf(missingOrderId).equals(record.key()));
        observations.add(observe("missing", missingOrderId, missingEventId, null));

        // ---- 사례 2: hook 통과, 실제 broker 까지 ----
        fakePublisher.beforeSend(event -> { });
        Long deliveredOrderId = createOrder(userId, productA, productB);
        PaymentAttempt deliveredAttempt = startPayment(userId, deliveredOrderId, "gap-2");
        fakePaymentGateway.markSuccess(deliveredAttempt.getTransactionKey());

        OrderConfirmInfo deliveredInfo = paymentFacade.applyCallback(deliveredAttempt.getProviderOrderId(), deliveredAttempt.getTransactionKey());

        // assert 2 - broker 의 record 가 DB 사실과 일치한다
        assertThat(deliveredInfo.settled()).isTrue();
        assertThat(fakePublisher.published()).hasSize(1);
        List<ConsumerRecord<String, byte[]>> records = readAll();
        assertThat(records).hasSize(1);
        ConsumerRecord<String, byte[]> record = records.get(0);
        JsonNode value = objectMapper.readTree(record.value());
        String deliveredEventId = "order-confirmed:v1:" + deliveredOrderId;
        assertThat(record.key()).isEqualTo(String.valueOf(deliveredOrderId));
        assertThat(value.get("eventId").asText()).isEqualTo(deliveredEventId);
        assertThat(value.get("orderId").asLong()).isEqualTo(deliveredOrderId);
        assertThat(value.get("confirmedAt").asText()).isEqualTo(CONFIRMED_AT.toString());
        assertThat(orderOf(deliveredOrderId).getConfirmedAt().toInstant()).isEqualTo(CONFIRMED_AT);
        assertThat(value.get("items")).hasSize(2);
        assertThat(value.get("items").get(0).get("productId").asLong()).isEqualTo(productA);
        assertThat(value.get("items").get(0).get("quantity").asInt()).isEqualTo(2);
        assertThat(value.get("items").get(1).get("productId").asLong()).isEqualTo(productB);
        assertThat(value.get("items").get(1).get("quantity").asInt()).isEqualTo(1);
        assertThat(reservationStatusesOf(deliveredOrderId)).containsOnly(StockReservation.Status.CONSUMED);
        assertThat(fakePaymentGateway.createCount()).isEqualTo(2);
        observations.add(observe("delivered", deliveredOrderId, deliveredEventId, record));

        // 관찰 기록 (W7 재현용). streamer 재생 테스트는 delivered 의 key·value 를 그대로 쓴다
        Files.createDirectories(OBSERVATION.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(OBSERVATION.toFile(), observations);
    }

    private Map<String, Object> observe(String role, Long orderId, String eventId, ConsumerRecord<String, byte[]> record) {
        Order order = orderJpaRepository.findWithItemsById(orderId).orElseThrow();
        PaymentAttempt attempt = attemptOf(orderId);
        Map<String, Object> observation = new LinkedHashMap<>();
        observation.put("role", role);
        observation.put("orderId", orderId);
        observation.put("providerOrderId", attempt.getProviderOrderId());
        observation.put("transactionKey", attempt.getTransactionKey());
        observation.put("eventId", eventId);
        observation.put("confirmedAt", order.getConfirmedAt().toInstant().toString());
        observation.put("items", order.getItems().stream()
            .map(item -> Map.of("productId", item.getProductId(), "quantity", item.getQuantity()))
            .toList());
        observation.put("orderStatus", order.getStatus().name());
        observation.put("attemptStatus", attempt.getStatus().name());
        observation.put("reservationStatuses", reservationStatusesOf(orderId).stream().map(Enum::name).toList());
        observation.put("brokerRecord", record == null ? null : Map.of(
            "topic", record.topic(), "partition", record.partition(), "offset", record.offset(),
            "key", record.key(), "value", new String(record.value(), StandardCharsets.UTF_8)));
        observation.put("purchaseActivity", record == null ? "없음 (발행되지 않음)" : "streamer OrderConfirmedReplayTest 에서 소비");
        return observation;
    }

    /** 이 클래스 전용 topic 의 partition 0 을 처음부터 끝까지 읽는다. */
    private List<ConsumerRecord<String, byte[]>> readAll() {
        Map<String, Object> props = Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestContainersConfig.bootstrapServers(),
            ConsumerConfig.GROUP_ID_CONFIG, "delivery-gap-" + UUID.randomUUID(),
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class,
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        List<ConsumerRecord<String, byte[]>> records = new ArrayList<>();
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(props)) {
            TopicPartition partition = new TopicPartition(TOPIC, 0);
            consumer.assign(List.of(partition));
            consumer.seekToBeginning(List.of(partition));
            long end = consumer.endOffsets(List.of(partition)).get(partition);
            long deadline = System.currentTimeMillis() + 10_000;
            while (consumer.position(partition) < end && System.currentTimeMillis() < deadline) {
                consumer.poll(Duration.ofMillis(500)).forEach(records::add);
            }
        }
        return records;
    }

    private Long saveProduct(String name, long price, int stock) {
        Product product = new Product(1L, name, new Price(price));
        product.changeStock(stock);
        return productJpaRepository.save(product).getId();
    }

    private Long createOrder(Long userId, Long productA, Long productB) {
        return orderFacade.createOrder(userId, List.of(new OrderCommand.Item(productA, 2), new OrderCommand.Item(productB, 1))).orderId();
    }

    private PaymentAttempt startPayment(Long userId, Long orderId, String attemptKey) {
        orderFacade.confirmOrder(userId, orderId, new OrderCommand.Confirm(attemptKey, CARD_TYPE, CARD_NO));
        return attemptOf(orderId);
    }

    private Order orderOf(Long orderId) {
        return orderJpaRepository.findById(orderId).orElseThrow();
    }

    private PaymentAttempt attemptOf(Long orderId) {
        return paymentAttemptJpaRepository.findByOrderId(orderId).orElseThrow();
    }

    private List<StockReservation.Status> reservationStatusesOf(Long orderId) {
        return stockReservationJpaRepository.findAllByOrderId(orderId).stream().map(StockReservation::getStatus).toList();
    }
}
