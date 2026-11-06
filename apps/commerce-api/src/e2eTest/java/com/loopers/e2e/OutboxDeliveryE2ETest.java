package com.loopers.e2e;

import com.loopers.CommerceApiApplication;
import com.loopers.CommerceStreamerApplication;
import com.loopers.application.order.OrderOutboxRelay;
import com.loopers.application.order.OrderOutboxRelayProperties;
import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.order.OrderConfirmedEvent;
import com.loopers.domain.order.OrderEventPublication;
import com.loopers.domain.order.OrderOutboxModel;
import com.loopers.domain.order.OrderOutboxService;
import com.loopers.domain.order.OrderOutboxStatus;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.order.ReservationStatus;
import com.loopers.domain.payment.ProviderOrderId;
import com.loopers.domain.point.PointModel;
import com.loopers.domain.product.ProductModel;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.order.KafkaOrderOutboxSender;
import com.loopers.infrastructure.order.OrderJpaRepository;
import com.loopers.infrastructure.order.OrderOutboxJpaRepository;
import com.loopers.infrastructure.order.StockReservationJpaRepository;
import com.loopers.infrastructure.point.PointJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.interfaces.consumer.DemoKafkaConsumer;
import com.loopers.interfaces.consumer.OrderConfirmedListener;
import com.loopers.support.kafka.BrokerRecords;
import com.loopers.support.outbox.RecordingOutboxSender;
import com.loopers.support.outbox.RelayStopHook;
import com.loopers.support.pg.PaymentTestSupport;
import com.loopers.support.pg.TestPgResetExtension;
import com.loopers.support.pg.TestPgServer;
import com.loopers.support.pg.TestPgStatus;
import com.loopers.testcontainers.KafkaBrokerFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.test.autoconfigure.filter.TypeExcludeFilters;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 주문 API부터 구매 활동까지 한 실행으로 확인한다 (W7 과제 7절, docs/week7/design.md 4-3절).
 *
 * <p>기존 주문 API·로컬 PG fixture·실제 MySQL·실제 Kafka·실제 구매 활동 consumer(commerce-streamer의 listener factory·parser·listener·handler)를
 * 잇는다. 가짜 handler로 업무 완료를 대신하지 않는다. relay는 첫 broker 확인 뒤 PUBLISHED 저장 전에 멈추고({@link RelayStopHook}),
 * 같은 Outbox에서 새 relay로 재시작한다.
 *
 * <p>context 구성 (결정 B): commerce-api 앱 하나의 context에서 streamer 클래스는 component scan에서 모두 빼고({@link ExcludeStreamerFromScan}),
 * 실제로 쓰는 consumer 부품만 {@link PurchaseActivityConsumerConfig}로 명시한다. streamer는 이 source set의 classpath에만 있고 제품 runtime 의존성이 아니다.
 *
 * <p>자원: 이 테스트만 쓰는 topic·group·상품·사용자·주문을 만든다. 모든 단계를 실제 commit으로 진행하고(테스트 트랜잭션 없음),
 * DB를 일괄 초기화하지 않는다. 끝에는 자기가 시작한 consumer와 만든 producer만 멈춘다(PG fixture는 {@link TestPgResetExtension}이 되돌린다).
 */
@SpringBootTest(classes = CommerceApiApplication.class)
@AutoConfigureMockMvc
@TypeExcludeFilters(ExcludeStreamerFromScan.class)
@Import(PurchaseActivityConsumerConfig.class)
@ExtendWith({TestPgResetExtension.class, OutputCaptureExtension.class})
class OutboxDeliveryE2ETest {

    private static final Logger log = LoggerFactory.getLogger(OutboxDeliveryE2ETest.class);
    private static final String TOPIC = KafkaBrokerFixture.createTopic("order-events-e2e", 1);
    private static final String GROUP = KafkaBrokerFixture.uniqueName("purchase-activity-e2e");
    private static final Duration DEADLINE = Duration.ofSeconds(60);

    private static final long USER_ID = 70_001L;
    private static final long INITIAL_BALANCE = 100_000L;
    private static final int INITIAL_STOCK = 10;

    @DynamicPropertySource
    static void kafka(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KafkaBrokerFixture::bootstrapServers);
        registry.add("order-event.topic-name", () -> TOPIC);
        registry.add("order-event.consumer-group", () -> GROUP);
        // consumer는 테스트가 자원을 준비한 뒤 직접 시작하고 끝에서 멈춘다
        registry.add("order-event.listener-auto-startup", () -> "false");
    }

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private BrandJpaRepository brandJpaRepository;

    @Autowired
    private ProductJpaRepository productJpaRepository;

    @Autowired
    private PointJpaRepository pointJpaRepository;

    @Autowired
    private OrderJpaRepository orderJpaRepository;

    @Autowired
    private StockReservationJpaRepository stockReservationJpaRepository;

    @Autowired
    private OrderOutboxJpaRepository orderOutboxJpaRepository;

    @Autowired
    private OrderOutboxService orderOutboxService;

    @Autowired
    private OrderOutboxRelayProperties relayProperties;

    @Autowired
    private KafkaProperties kafkaProperties;

    @Autowired
    private Clock clock;

    @Autowired
    private KafkaListenerEndpointRegistry listenerRegistry;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Value("${order-outbox.relay.send-timeout}")
    private Duration sendTimeout;

    private final TestPgServer pg = TestPgServer.shared();
    private final List<KafkaOrderOutboxSender> producers = new ArrayList<>();

    @AfterEach
    void stopOwnResources() throws Exception {
        listenerRegistry.getListenerContainer(OrderConfirmedListener.LISTENER_ID).stop();
        for (KafkaOrderOutboxSender producer : producers) {
            producer.destroy();
        }
    }

    @DisplayName("주문 API로 만든 주문을 PG 성공으로 확정하고 relay가 broker 확인 뒤 PUBLISHED 저장 전에 멈췄다가 재시작해도, "
        + "consumer는 같은 사건을 두 번 받고 구매 활동은 상품별 한 번(수량 2·1)만 남는다.")
    @Test
    void deliversConfirmedOrderToPurchaseActivity_acrossRelayRestart(CapturedOutput output) throws Exception {
        // context: streamer는 명시한 consumer 부품만 올라왔고, streamer 앱 설정·demo consumer는 scan되지 않았다 (결정 B)
        assertThat(applicationContext.getBeanNamesForType(OrderConfirmedListener.class)).hasSize(1);
        assertThat(applicationContext.getBeanNamesForType(CommerceStreamerApplication.class)).isEmpty();
        assertThat(applicationContext.getBeanNamesForType(DemoKafkaConsumer.class)).isEmpty();

        // 0. 자원 준비: 합성 상품 두 개(7×2, 8×1에 해당)와 사용자 포인트, 그리고 실제 consumer 시작
        BrandModel brand = brandJpaRepository.save(new BrandModel("e2e-brand"));
        ProductModel productA = productJpaRepository.save(new ProductModel(brand.getId(), "e2e-product-a", 7_000L, INITIAL_STOCK));
        ProductModel productB = productJpaRepository.save(new ProductModel(brand.getId(), "e2e-product-b", 8_000L, INITIAL_STOCK));
        PointModel point = new PointModel(USER_ID);
        point.charge(INITIAL_BALANCE);
        pointJpaRepository.save(point);
        MessageListenerContainer consumer = listenerRegistry.getListenerContainer(OrderConfirmedListener.LISTENER_ID);
        consumer.start();
        await().atMost(DEADLINE).until(consumer::isRunning);

        // 1. 기존 API로 주문을 만들고, PG SUCCESS를 callback 경로(내부 확정 경로)에 적용한다
        Map<Long, Integer> items = new LinkedHashMap<>();
        items.put(productA.getId(), 2);
        items.put(productB.getId(), 1);
        Long orderId = E2eSupport.createOrder(mvc, USER_ID, items);
        E2eSupport.confirm(mvc, USER_ID, orderId).andExpect(status().isAccepted());
        PaymentTestSupport.completeAt(pg, orderId, TestPgStatus.SUCCESS);
        E2eSupport.callbackOf(mvc, PaymentTestSupport.onlyTransactionOf(pg, orderId)).andExpect(status().isOk());

        // 2. 확정 주문·예약 소비·같은 eventId의 Outbox 한 행
        String eventId = OrderConfirmedEvent.eventIdOf(orderId);
        assertThat(orderJpaRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(stockReservationJpaRepository.findByOrderId(orderId).orElseThrow().getStatus()).isEqualTo(ReservationStatus.CONSUMED);
        OrderOutboxModel recorded = onlyOutboxRow(orderId);
        assertThat(recorded.getEventId()).isEqualTo(eventId);
        assertThat(recorded.getStatus()).isEqualTo(OrderOutboxStatus.PENDING);
        assertThat(stockOf(productA)).isEqualTo(INITIAL_STOCK - 2);
        assertThat(stockOf(productB)).isEqualTo(INITIAL_STOCK - 1);

        // 3. relay의 첫 broker 확인 뒤 PUBLISHED 저장 전에 멈춘다
        RelayStopHook stopBeforePublishedSave = RelayStopHook.before(RelayStopHook.Stage.MARK_PUBLISHED, orderOutboxService);
        RecordingOutboxSender firstSender = RecordingOutboxSender.of(newProducer());
        OrderOutboxRelay stoppedRelay = new OrderOutboxRelay(stopBeforePublishedSave, firstSender, relayProperties, clock);
        assertThatThrownBy(stoppedRelay::runOnce).isInstanceOf(RelayStopHook.RelayStopped.class);
        OrderEventPublication.Sent firstAck = onlySent(firstSender);
        assertThat(onlyOutboxRow(orderId).getStatus()).as("broker는 받았지만 PUBLISHED는 저장하지 않았다").isEqualTo(OrderOutboxStatus.PENDING);

        // consumer는 첫 record를 처리한다. Outbox가 아직 PENDING이어도 구매 활동은 생긴다(PUBLISHED는 소비 완료와 무관하다)
        await().atMost(DEADLINE).untilAsserted(() -> assertThat(E2eSupport.committedOffset(GROUP, TOPIC)).isEqualTo(firstAck.offset() + 1));
        assertThat(E2eSupport.purchaseActivities(jdbcTemplate, orderId)).containsExactly(tuple(productA.getId(), 2), tuple(productB.getId(), 1));

        // 4. 같은 Outbox에서 relay를 재시작한다. 새 relay 객체·새 producer, 같은 DB·topic·eventId. Outbox 상태는 직접 고치지 않는다
        RecordingOutboxSender restartedSender = RecordingOutboxSender.of(newProducer());
        OrderOutboxRelay.Run restarted = new OrderOutboxRelay(orderOutboxService, restartedSender, relayProperties, clock).runOnce();
        assertThat(restarted).isEqualTo(new OrderOutboxRelay.Run(true, 1, 1, 0));
        OrderEventPublication.Sent secondAck = onlySent(restartedSender);

        // 5. consumer가 같은 사건을 다시 처리한 뒤(두 번째 record까지 ack) DB의 상품별 구매 사실을 확인한다
        await().atMost(DEADLINE).untilAsserted(() -> assertThat(E2eSupport.committedOffset(GROUP, TOPIC)).isEqualTo(secondAck.offset() + 1));

        // 주문·PG 거래: 같은 확정 주문, 외부 거래 1
        assertThat(orderJpaRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(pg.transactionsOf(ProviderOrderId.from(orderId))).hasSize(1);
        assertThat(pg.createCount()).isEqualTo(1);

        // Outbox: 같은 eventId 한 행, 최종 PUBLISHED
        OrderOutboxModel published = onlyOutboxRow(orderId);
        assertThat(published.getId()).isEqualTo(recorded.getId());
        assertThat(published.getEventId()).isEqualTo(eventId);
        assertThat(published.getStatus()).isEqualTo(OrderOutboxStatus.PUBLISHED);
        assertThat(published.getAttemptCount()).isEqualTo(2);

        // Kafka: 같은 eventId의 별도 record 2개(다른 offset). send 호출 수가 아니라 broker에 실제로 쓰인 기록을 본다
        List<BrokerRecords.Record> records = BrokerRecords.readAll(TOPIC);
        assertThat(records).extracting(BrokerRecords.Record::offset).containsExactly(firstAck.offset(), secondAck.offset());
        assertThat(records).allSatisfy(record -> {
            assertThat(record.key()).isEqualTo(String.valueOf(orderId));
            assertThat(record.value()).isEqualTo(recorded.getPayload());
            assertThat(E2eSupport.eventIdIn(record.value())).isEqualTo(eventId);
        });

        // consumer: 같은 사건을 두 번 받았다. 처음은 반영, 다음은 이미 반영된 사실로 no-op
        assertThat(output.getOut())
            .contains("result=RECORDED, eventId=" + eventId + ", orderId=" + orderId + ", partition=0, offset=" + firstAck.offset())
            .contains("result=ALREADY_PROCESSED, eventId=" + eventId + ", orderId=" + orderId + ", partition=0, offset=" + secondAck.offset());

        // 완료 기록: 같은 orderId·eventId로 1
        assertThat(E2eSupport.processedEventIds(jdbcTemplate, orderId)).containsExactly(eventId);

        // 구매 활동: 상품별 2행, 수량 2·1 유지
        assertThat(E2eSupport.purchaseActivities(jdbcTemplate, orderId)).containsExactly(tuple(productA.getId(), 2), tuple(productB.getId(), 1));

        // 재고·포인트: 예약 추가 소비·반환 없음, PG 주문이라 포인트 차감 없음
        assertThat(stockReservationJpaRepository.findByOrderId(orderId).orElseThrow().getStatus()).isEqualTo(ReservationStatus.CONSUMED);
        assertThat(stockOf(productA)).isEqualTo(INITIAL_STOCK - 2);
        assertThat(stockOf(productB)).isEqualTo(INITIAL_STOCK - 1);
        assertThat(pointJpaRepository.findByUserId(USER_ID).orElseThrow().getBalance()).isEqualTo(INITIAL_BALANCE);

        log.info("[W7 E2E] topic={} group={} orderId={} eventId={} | 첫 발행 offset={}(PUBLISHED 저장 전 중단) → 재발행 offset={} | "
                + "Outbox={}(시도 {}) | 완료 기록 1 | 구매 활동 {}",
            TOPIC, GROUP, orderId, eventId, firstAck.offset(), secondAck.offset(),
            published.getStatus(), published.getAttemptCount(), E2eSupport.purchaseActivities(jdbcTemplate, orderId));
    }

    /** 운영 설정(KafkaProperties·topic·send-timeout)으로 만든 relay 전용 producer. 재시작마다 새로 만든다. */
    private KafkaOrderOutboxSender newProducer() {
        KafkaOrderOutboxSender producer = new KafkaOrderOutboxSender(kafkaProperties, TOPIC, sendTimeout);
        producers.add(producer);
        return producer;
    }

    private static OrderEventPublication.Sent onlySent(RecordingOutboxSender sender) {
        List<OrderEventPublication> results = sender.results();
        assertThat(results).hasSize(1);
        assertThat(results.get(0)).as("broker가 받았다는 확인").isInstanceOf(OrderEventPublication.Sent.class);
        return (OrderEventPublication.Sent) results.get(0);
    }

    private OrderOutboxModel onlyOutboxRow(Long orderId) {
        List<OrderOutboxModel> rows = orderOutboxJpaRepository.findAllByOrderId(orderId);
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    private int stockOf(ProductModel product) {
        return productJpaRepository.findById(product.getId()).orElseThrow().getStock();
    }
}
