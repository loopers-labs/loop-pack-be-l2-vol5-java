package com.loopers.infrastructure.order;

import com.loopers.application.order.OrderConfirmFacade;
import com.loopers.application.order.OrderConfirmedEventListener;
import com.loopers.application.order.OrderFacade;
import com.loopers.application.payment.PaymentApplyResult;
import com.loopers.application.point.PointFacade;
import com.loopers.config.OrderEventKafkaConfig;
import com.loopers.domain.order.OrderEventPublisher;
import com.loopers.domain.order.OrderItemCommand;
import com.loopers.domain.payment.PaymentModel;
import com.loopers.domain.product.ProductModel;
import com.loopers.fixture.PgCommands;
import com.loopers.fixture.PgFixtureContextInitializer;
import com.loopers.fixture.ProductFixture;
import com.loopers.fixture.UserFixture;
import com.loopers.fixture.pg.CreateOutcome;
import com.loopers.fixture.pg.PgFixtureServer;
import com.loopers.fixture.pg.PgOperation;
import com.loopers.fixture.pg.ResponsePlan;
import com.loopers.infrastructure.order.KafkaOrderEventPublisher.OrderConfirmedV1;
import com.loopers.utils.DatabaseCleanUp;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 실제 Spring TX·테스트 MySQL·로컬 PG fixture·실제 listener·실제 Kafka adapter 를 연결하고, 전용 KafkaTemplate 만 대역으로 바꿔
 * commit 뒤 send 호출 오류와 Future 실패를 통제한다(P5 오류 연결).
 *
 * <p>대역 template 은 producer 를 만들지 않으므로 broker 전송·record 부재의 근거가 아니며 P6·P7 검증도 아니다.
 * Future 실패·timeout 은 broker 미기록의 증거가 아니라 이 앱이 결과를 실패로 관측했다는 뜻이다.
 * 기록용 fake 를 쓰지 않으므로 fake 회귀와 다른 Spring 컨텍스트로 뜬다.
 */
@SpringBootTest
@ContextConfiguration(initializers = PgFixtureContextInitializer.class)
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("실제 adapter 의 send 호출 오류와 Future 실패는 commit 된 최초 PG 확정을 바꾸지 않고, 단계별 안전 로그만 남긴다.")
class OrderConfirmedSendFailureIntegrationTest {

    private static final String TOPIC = "order-events";
    private static final String RAW_MESSAGE = "raw-failure-message-not-for-log";
    private static final long PRICE = 2_000L;
    private static final long STOCK = 5L;
    private static final long QUANTITY = 2L;
    private static final Duration QUIET = Duration.ofMillis(500);

    @MockitoBean(name = OrderEventKafkaConfig.ORDER_EVENT_KAFKA_TEMPLATE, enforceOverride = true)
    private KafkaTemplate<String, Object> template;

    @Autowired private ApplicationContext context;
    @Autowired private OrderEventPublisher port;
    @Autowired private OrderConfirmedEventListener listener;
    @Autowired @Qualifier(OrderEventKafkaConfig.ORDER_EVENT_KAFKA_TEMPLATE) private KafkaTemplate<String, Object> qualifiedTemplate;
    @Autowired private PgFixtureServer pg;
    @Autowired private OrderFacade orders;
    @Autowired private OrderConfirmFacade confirmFacade;
    @Autowired private UserFixture users;
    @Autowired private ProductFixture products;
    @Autowired private PointFacade points;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DatabaseCleanUp cleanup;

    private Long user;
    private ProductModel product;
    private Long order;

    @BeforeEach
    void prepare() {
        pg.reset();
        user = users.createUserWithPoint().getId();
        points.charge(user, 10_000L);
        product = products.createProduct("발행 실패 상품", PRICE, STOCK);
        order = orders.create(user, List.of(new OrderItemCommand(product.getId(), QUANTITY))).id();
        pg.enqueuePlan(PgOperation.CREATE, PaymentModel.providerOrderIdOf(order), ResponsePlan.outcome(CreateOutcome.SUCCESS));
    }

    @AfterEach
    void cleanUp() {
        await().atMost(Duration.ofSeconds(5)).until(() ->
            pg.state().activeRequests() == 0 && pg.state().activeCallbackJobs() == 0);
        cleanup.truncateAllTables();
    }

    /** 주문·결제·재고·재고 이력을 새 연결로 읽는다. 시각은 DB 원문 마이크로초 문자열로 비교한다. */
    private Map<String, Object> committedFacts() {
        return Map.of(
            "order", jdbc.queryForMap("SELECT status, paid_amount, "
                + "DATE_FORMAT(confirmed_at, '%Y-%m-%d %H:%i:%s.%f') AS confirmed_at FROM orders WHERE id = ?", order),
            "payment", jdbc.queryForMap("SELECT status, amount, transaction_key FROM payment WHERE order_id = ?", order),
            "stock", jdbc.queryForObject("SELECT stock_quantity FROM product WHERE id = ?", Long.class, product.getId()),
            "reservations", histories("ORDER_RESERVATION"),
            "releases", histories("ORDER_RELEASE"));
    }

    private long histories(String cause) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM stock_history WHERE order_id = ? AND cause = ?", Long.class, order, cause);
    }

    private long pgCreates() {
        return pg.state().counts().createReceived();
    }

    private long sendCalls() {
        return mockingDetails(template).getInvocations().stream()
            .filter(invocation -> invocation.getMethod().getName().equals("send"))
            .count();
    }

    @SuppressWarnings("unchecked")
    private OrderConfirmedV1 sentPayload() {
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(template).send(eq(TOPIC), eq(Long.toString(order)), payload.capture());
        return (OrderConfirmedV1) payload.getValue();
    }

    private void assertConfirmed(PaymentApplyResult result, Map<String, Object> facts) {
        @SuppressWarnings("unchecked")
        Map<String, Object> orderRow = (Map<String, Object>) facts.get("order");
        @SuppressWarnings("unchecked")
        Map<String, Object> paymentRow = (Map<String, Object>) facts.get("payment");
        assertAll(
            () -> assertThat(result.outcome()).isEqualTo(PaymentApplyResult.Outcome.FINAL),
            () -> assertThat(result.order().status()).isEqualTo("CONFIRMED"),
            () -> assertThat(orderRow.get("status")).isEqualTo("CONFIRMED"),
            () -> assertThat(((Number) orderRow.get("paid_amount")).longValue()).isEqualTo(PRICE * QUANTITY),
            () -> assertThat(orderRow.get("confirmed_at")).isNotNull(),
            () -> assertThat(paymentRow.get("status")).isEqualTo("SUCCESS"),
            () -> assertThat(((Number) paymentRow.get("amount")).longValue()).isEqualTo(PRICE * QUANTITY),
            () -> assertThat(facts.get("stock")).isEqualTo(STOCK - QUANTITY),
            () -> assertThat(facts.get("reservations")).isEqualTo(1L),
            () -> assertThat(facts.get("releases")).isEqualTo(0L)
        );
    }

    private void assertWiredToRealAdapter() {
        assertAll(
            () -> assertThat(context.getBeansOfType(OrderEventPublisher.class)).containsOnlyKeys("kafkaOrderEventPublisher"),
            () -> assertThat(port).isInstanceOf(KafkaOrderEventPublisher.class),
            () -> assertThat(ReflectionTestUtils.getField(listener, "publisher")).isSameAs(port),
            () -> assertThat(qualifiedTemplate).isSameAs(template),
            () -> assertThat(ReflectionTestUtils.getField(port, "template")).isSameAs(template)
        );
    }

    private void assertSafeFailureLog(CapturedOutput output, String stage, String exceptionType, String causeType) {
        String eventId = "order-confirmed:" + order;
        assertAll(
            () -> assertThat(output.getAll()).contains("ORDER_CONFIRMED_SEND_FAILURE eventId=" + eventId + " orderId=" + order
                + " topic=" + TOPIC + " stage=" + stage + " exceptionType=" + exceptionType + " causeType=" + causeType),
            () -> assertThat(output.getAll()).as("원문 예외 메시지").doesNotContain(RAW_MESSAGE),
            () -> assertThat(output.getAll()).as("이 테스트에서 만든 예외의 stacktrace").doesNotContain("at " + getClass().getName()),
            () -> assertThat(output.getAll()).as("payload").doesNotContain("OrderConfirmedV1[").doesNotContain("schemaVersion"),
            () -> assertThat(output.getAll()).as("listener 중복 로그").doesNotContain("stage=LISTENER_CALL"),
            () -> assertThat(output.getAll()).doesNotContain("ORDER_CONFIRMED_SEND_SUCCESS")
        );
    }

    @DisplayName("send 호출이 예외를 던지면 commit 된 확정·결제·시각·예약 소비가 그대로이고, SEND_CALL 로그만 남으며 PG 생성·재전송이 더 없다.")
    @Test
    void sendCallFailureKeepsCommittedConfirmation(CapturedOutput output) {
        when(template.send(anyString(), anyString(), any())).thenThrow(new IllegalStateException(RAW_MESSAGE));

        PaymentApplyResult result = confirmFacade.confirm(user, order, PgCommands.forOrder(order));
        Map<String, Object> afterConfirm = committedFacts();
        long createsAfterConfirm = pgCreates();

        await().during(QUIET).atMost(QUIET.multipliedBy(4)).until(() -> sendCalls() == 1 && pgCreates() == createsAfterConfirm);

        OrderConfirmedV1 payload = sentPayload();
        assertWiredToRealAdapter();
        assertConfirmed(result, afterConfirm);
        assertAll(
            () -> assertThat(createsAfterConfirm).as("PG fixture create 수신").isEqualTo(1L),
            () -> assertThat(committedFacts()).as("대기 뒤 새 조회").isEqualTo(afterConfirm),
            () -> assertThat(payload.eventId()).isEqualTo("order-confirmed:" + order),
            () -> assertThat(payload.orderId()).isEqualTo(order)
        );
        assertSafeFailureLog(output, "SEND_CALL", IllegalStateException.class.getName(), "none");
    }

    @DisplayName("send 가 돌려준 Future 가 확정 반환·commit 뒤에 실패해도 확정·결제·시각·예약 소비는 그대로이고, SEND_COMPLETION 로그만 남으며 PG 생성·재전송이 더 없다.")
    @Test
    void futureFailureKeepsCommittedConfirmation(CapturedOutput output) {
        CompletableFuture<SendResult<String, Object>> pending = new CompletableFuture<>();
        when(template.send(anyString(), anyString(), any())).thenReturn(pending);

        PaymentApplyResult result = confirmFacade.confirm(user, order, PgCommands.forOrder(order));
        boolean pendingWhenReturned = !pending.isDone();
        Map<String, Object> afterConfirm = committedFacts();
        long createsAfterConfirm = pgCreates();
        assertConfirmed(result, afterConfirm);
        assertThat(output.getAll()).as("Future 완료 전에는 결과 로그가 없음").doesNotContain("ORDER_CONFIRMED_SEND_");

        pending.completeExceptionally(new KafkaException(RAW_MESSAGE, new TimeoutException(RAW_MESSAGE)));
        await().during(QUIET).atMost(QUIET.multipliedBy(4)).until(() -> sendCalls() == 1 && pgCreates() == createsAfterConfirm);

        OrderConfirmedV1 payload = sentPayload();
        assertWiredToRealAdapter();
        assertAll(
            () -> assertThat(pendingWhenReturned).as("확정 반환 시점에 Future 미완료").isTrue(),
            () -> assertThat(createsAfterConfirm).as("PG fixture create 수신").isEqualTo(1L),
            () -> assertThat(committedFacts()).as("Future 실패 뒤 새 조회").isEqualTo(afterConfirm),
            () -> assertThat(payload.eventId()).isEqualTo("order-confirmed:" + order)
        );
        assertSafeFailureLog(output, "SEND_COMPLETION", KafkaException.class.getName(), TimeoutException.class.getName());
    }
}
