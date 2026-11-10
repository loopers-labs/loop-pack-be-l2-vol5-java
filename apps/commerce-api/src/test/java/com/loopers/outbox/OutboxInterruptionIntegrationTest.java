package com.loopers.outbox;

import com.loopers.brand.adapter.out.persistence.BrandJpaRepository;
import com.loopers.brand.domain.BrandModel;
import com.loopers.order.adapter.out.persistence.OrderJpaRepository;
import com.loopers.order.application.port.in.OrderCommandUseCase;
import com.loopers.order.domain.OrderLines;
import com.loopers.order.domain.OrderStatus;
import com.loopers.outbox.adapter.out.persistence.OrderOutboxJpaRepository;
import com.loopers.outbox.application.port.in.OrderOutboxRelayUseCase;
import com.loopers.outbox.application.port.in.OrderOutboxRelayUseCase.RelayResult;
import com.loopers.outbox.application.port.out.OrderOutboxPort;
import com.loopers.outbox.domain.OrderOutbox;
import com.loopers.outbox.domain.OrderOutboxStatus;
import com.loopers.payment.application.PaymentResultApplier;
import com.loopers.payment.application.port.in.PaymentDispatchUseCase;
import com.loopers.payment.application.port.in.PaymentInfo;
import com.loopers.payment.application.port.in.PaymentRequestCommand;
import com.loopers.payment.application.port.in.PaymentRequestUseCase;
import com.loopers.payment.application.port.out.PaymentGatewayPort;
import com.loopers.payment.application.port.out.PaymentPort;
import com.loopers.payment.application.port.out.PgPaymentRequest;
import com.loopers.payment.domain.Payment;
import com.loopers.product.adapter.out.persistence.ProductJpaRepository;
import com.loopers.product.domain.ProductModel;
import com.loopers.support.pg.LocalTestPgServer;
import com.loopers.testcontainers.KafkaTestContainersConfig;
import com.loopers.user.adapter.out.persistence.UserJpaRepository;
import com.loopers.user.domain.UserModel;
import com.loopers.utils.DatabaseCleanUp;
import com.loopers.utils.KafkaTopicReader;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * C-5 두 중단 지점 (docs/week7/plan.md 5단계). 실제 확정 경로(로컬 PG 조회 → 결과 적용)와 실제 broker(Testcontainers)로 본다.
 * <p>
 * 중단은 W6 E-4와 같은 방식의 <b>통제된 지점</b>이다. 프로세스를 죽이는 대신, 죽었을 때와 같은 DB·broker 상태를 정확히 만든다.
 * <ol>
 *   <li>commit 뒤 relay 전 — 확정 TX가 commit된 뒤 relay가 돌지 않았다(= 그 사이 죽음). 재시작 뒤 첫 회차가 보낸다</li>
 *   <li>broker 확인 뒤 상태 저장 전 — ack를 받고 PUBLISHED를 저장하는 TX2가 실패했다(= 그 순간 죽음). 행은 SENDING으로 남고,
 *       lease(SENDING 표시의 유효 기한, 1분)가 지나면 같은 eventId·payload로 다시 나간다 → broker에 같은 사건이 2건(중복)</li>
 * </ol>
 * 과제 문구는 ②에서 "같은 Outbox PENDING 유지"를 기대하지만, 이 설계는 SENDING + lease다(plan.md D-0 "SENDING을 둔다" — PR에 적는다).
 * 중복 2건이 구매 효과를 늘리지 않는 것은 consumer 쪽 확인(C-6, 7단계 E2E)이다.
 * <p>
 * 시계는 고정한 Clock으로 lease 경과를 옮긴다. 공유 broker에는 다른 테스트의 record도 있으므로 이 테스트의 eventId로 찾는다.
 */
@SpringBootTest(properties = "pg.local.lookup-read-timeout=300ms")
class OutboxInterruptionIntegrationTest {

    private static final LocalTestPgServer PG = LocalTestPgServer.start();
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @DynamicPropertySource
    static void pgProperties(DynamicPropertyRegistry registry) {
        registry.add("pg.local.base-url", PG::baseUrl);
    }

    @AfterAll
    static void stopPg() {
        PG.stop();
    }

    @Autowired
    private PaymentResultApplier paymentResultApplier;

    @Autowired
    private PaymentRequestUseCase paymentRequestUseCase;

    @MockitoBean
    private PaymentDispatchUseCase paymentDispatchUseCase;

    @Autowired
    private PaymentGatewayPort paymentGatewayPort;

    @Autowired
    private PaymentPort paymentPort;

    @Autowired
    private OrderCommandUseCase orderCommandUseCase;

    @Autowired
    private OrderOutboxRelayUseCase relay;

    @MockitoSpyBean
    private OrderOutboxPort orderOutboxPort;

    @MockitoBean
    private Clock clock;

    @Autowired
    private OrderOutboxJpaRepository orderOutboxJpaRepository;

    @Autowired
    private OrderJpaRepository orderJpaRepository;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Autowired
    private BrandJpaRepository brandJpaRepository;

    @Autowired
    private ProductJpaRepository productJpaRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @Value("${kafka.topics.order-events}")
    private String orderEventsTopic;

    private ZonedDateTime t0;
    private Long orderId;

    @BeforeEach
    void setUp() {
        PG.reset();
        circuitBreakerRegistry.circuitBreaker("pgLookup").reset();
        t0 = ZonedDateTime.now(KST);
        at(t0);
        when(clock.getZone()).thenReturn(KST);
        orderId = confirmedOrder();
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private void at(ZonedDateTime now) {
        when(clock.instant()).thenReturn(now.toInstant());
    }

    /** 에어맥스 2개 주문을 PG SUCCESS 조회로 확정한다. 확정 TX가 order_outbox에 PENDING 행을 남기고 commit된다 */
    private Long confirmedOrder() {
        UserModel user = userJpaRepository.save(new UserModel("고객"));
        BrandModel nike = brandJpaRepository.save(new BrandModel("나이키", null));
        ProductModel airMax = productJpaRepository.save(new ProductModel(nike.getId(), "에어맥스", 1_000, 5));
        Long id = orderCommandUseCase.create(user.getId(), List.of(new OrderLines.Line(airMax.getId(), 2))).id();
        PaymentInfo info = paymentRequestUseCase.request(new PaymentRequestCommand(user.getId(), id, "attempt-1", "SAMSUNG", "1234-5678-9814-1451"));
        Payment payment = paymentPort.findById(info.paymentId()).orElseThrow();
        String transactionKey = paymentGatewayPort.request(new PgPaymentRequest(payment.getProviderOrderId(), "SAMSUNG", "1234-5678-9814-1451", 2_000L)).transactionKey();
        transactionTemplate.executeWithoutResult(status -> {
            Payment locked = paymentPort.findByIdForUpdate(info.paymentId()).orElseThrow();
            locked.markSending(ZonedDateTime.now());
            locked.recordAccepted(transactionKey, ZonedDateTime.now());
            paymentPort.save(locked);
        });
        PG.complete(transactionKey, "SUCCESS", null);
        paymentResultApplier.reconcile(info.paymentId());
        return id;
    }

    private OrderOutbox row() {
        return orderOutboxJpaRepository.findAll().stream().filter(r -> r.getOrderId().equals(orderId)).findFirst().orElseThrow();
    }

    private List<ConsumerRecord<String, String>> recordsOf(String eventId, int expected, Duration wait) {
        return KafkaTopicReader.read(KafkaTestContainersConfig.bootstrapServers(), orderEventsTopic,
            record -> record.value().contains("\"eventId\":\"" + eventId + "\""), expected, wait);
    }

    @DisplayName("C-5 ① commit 뒤 relay 전에 멈추면: 주문 CONFIRMED · 행 PENDING · broker 0건. 재시작 뒤 첫 회차가 저장된 eventId·payload 그대로 1건 보내고 PUBLISHED가 된다 — PG는 다시 부르지 않는다.")
    @Test
    void stoppedAfterCommitBeforeRelay() {
        // arrange: 확정 commit까지 끝났고 relay는 한 번도 돌지 않았다 (그 사이 프로세스가 멈춘 상태)
        OrderOutbox waiting = row();
        int createCalls = PG.createCalls();
        assertThat(orderJpaRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(waiting.getStatus()).isEqualTo(OrderOutboxStatus.PENDING);
        assertThat(recordsOf(waiting.getEventId(), 1, Duration.ofSeconds(3))).isEmpty();

        // act: 재시작 뒤 첫 회차 (1초 뒤)
        at(t0.plusSeconds(1));
        RelayResult result = relay.relayOnce();

        // assert
        assertThat(result).isEqualTo(new RelayResult(1, 1, null));
        List<ConsumerRecord<String, String>> records = recordsOf(waiting.getEventId(), 2, Duration.ofSeconds(5));
        assertThat(records).singleElement().satisfies(record -> {
            assertThat(record.key()).isEqualTo(String.valueOf(orderId));
            assertThat(record.value()).isEqualTo(waiting.getPayload());
        });
        assertThat(row().getStatus()).isEqualTo(OrderOutboxStatus.PUBLISHED);
        assertThat(row().getEventId()).isEqualTo(waiting.getEventId());
        assertThat(PG.createCalls()).isEqualTo(createCalls);
    }

    @DisplayName("C-5 ② broker 확인 뒤 상태 저장 전에 멈추면: broker에는 1건 있고 행은 SENDING(PUBLISHED 아님). lease가 남은 동안은 다시 보내지 않고, 지나면 같은 eventId·payload로 다시 보내 broker에 같은 사건 2건 · 행 PUBLISHED.")
    @Test
    void stoppedAfterAckBeforeSavingPublished() {
        // arrange: ack를 받은 뒤 PUBLISHED 저장 TX가 한 번 실패한다 (그 순간 프로세스가 멈춘 것과 같은 DB 상태)
        OrderOutbox waiting = row();
        doThrow(new IllegalStateException("PUBLISHED 저장 직전 중단 (주입)"))
            .doCallRealMethod()
            .when(orderOutboxPort).save(argThat(o -> o != null && o.getStatus() == OrderOutboxStatus.PUBLISHED));

        // act 1: 중단이 일어나는 회차
        RelayResult interrupted = relay.relayOnce();

        // assert 1: broker는 받았지만 DB는 PUBLISHED가 아니다 — 거짓 PUBLISHED도, 실패 횟수 증가도 없다
        assertThat(interrupted.stoppedAt()).isEqualTo(waiting.getEventId());
        assertThat(recordsOf(waiting.getEventId(), 1, Duration.ofSeconds(10))).hasSize(1);
        OrderOutbox stuck = row();
        assertThat(stuck.getStatus()).isEqualTo(OrderOutboxStatus.SENDING);
        assertThat(stuck.getPublishedAt()).isNull();
        assertThat(stuck.getAttemptCount()).isZero();
        assertThat(stuck.getNextAttemptAt().toInstant()).isEqualTo(t0.plus(OrderOutbox.LEASE).toInstant());

        // act 2·assert 2: 재시작했지만 lease(맡은 시각 + 1분) 전이면 보내는 중으로 보고 고르지 않는다
        at(t0.plusSeconds(59));
        assertThat(relay.relayOnce()).isEqualTo(new RelayResult(0, 0, null));

        // act 3: lease가 지나면 멈춘 것으로 보고 다시 맡는다
        at(t0.plus(OrderOutbox.LEASE));
        RelayResult resent = relay.relayOnce();

        // assert 3: 같은 eventId·payload가 broker에 2건(중복), 행은 PUBLISHED
        assertThat(resent).isEqualTo(new RelayResult(1, 1, null));
        List<ConsumerRecord<String, String>> records = recordsOf(waiting.getEventId(), 3, Duration.ofSeconds(5));
        assertThat(records).hasSize(2).allSatisfy(record -> {
            assertThat(record.key()).isEqualTo(String.valueOf(orderId));
            assertThat(record.value()).isEqualTo(waiting.getPayload());
        });
        assertThat(row().getStatus()).isEqualTo(OrderOutboxStatus.PUBLISHED);
        assertThat(row().getPublishedOffset()).isEqualTo(records.get(1).offset());
    }
}
