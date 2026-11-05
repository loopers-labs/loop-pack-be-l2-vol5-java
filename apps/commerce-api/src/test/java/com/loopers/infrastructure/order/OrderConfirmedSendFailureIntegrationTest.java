package com.loopers.infrastructure.order;

import com.loopers.application.order.OrderConfirmFacade;
import com.loopers.application.order.OrderFacade;
import com.loopers.application.order.OrderOutboxExecutionGate.Result;
import com.loopers.application.order.OrderOutboxOperations;
import com.loopers.application.order.OrderOutboxRelay;
import com.loopers.application.order.OrderOutboxRelay.RowOutcome;
import com.loopers.application.order.OrderOutboxRelay.RowResult;
import com.loopers.application.payment.PaymentApplyResult;
import com.loopers.application.point.PointFacade;
import com.loopers.config.OrderOutboxClockConfig;
import com.loopers.config.OrderOutboxKafkaConfig;
import com.loopers.domain.order.OrderItemCommand;
import com.loopers.domain.order.OrderOutboxSender;
import com.loopers.domain.payment.PaymentModel;
import com.loopers.domain.product.ProductModel;
import com.loopers.fixture.PgCommands;
import com.loopers.fixture.PgFixtureContextInitializer;
import com.loopers.fixture.ProductFixture;
import com.loopers.fixture.SettableClock;
import com.loopers.fixture.UserFixture;
import com.loopers.fixture.pg.CreateOutcome;
import com.loopers.fixture.pg.PgFixtureServer;
import com.loopers.fixture.pg.PgOperation;
import com.loopers.fixture.pg.ResponsePlan;
import com.loopers.utils.DatabaseCleanUp;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * O8/O9 의 실제 PG 확정 연결(설계 W7.4-4 전환표, 구현 08-2 W7 전환). 실제 주문·PG fixture 로 최초 확정을 commit 한 뒤, 자동 relay 를 끈 채
 * 명시 relay 로 실제 W7 sender(KafkaOrderOutboxSender)를 부른다. 대역은 그 sender 가 쓰는 전용 String template 하나뿐이다.
 *
 * <p>대역 template 은 producer 를 만들지 않으므로 broker 기록·미기록의 근거가 아니다. Future 실패는 이 앱이 결과를 실패로 관측했다는 뜻이다.
 * timeout 뒤 늦은 완료·10회 예산·BLOCKED·재개는 OrderOutboxRelayIntegrationTest 가 통제 sender 로 보호한다. W6 직접 발행 adapter 의
 * 오류 로그 기대는 KafkaOrderEventPublisherTest·W6 보존 테스트가 그대로 보호한다. 재시도 시각은 outboxRecordClock 만 통제한다.
 */
@SpringBootTest(properties = "commerce.order-events.relay.enabled=false")
@ContextConfiguration(initializers = PgFixtureContextInitializer.class)
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("최초 PG 확정 commit 뒤 W7 sender 의 send 호출 오류·Future 실패는 확정을 바꾸지 않고 안전 code 로 PENDING 에 남으며, 다음 시각에 같은 원문을 다시 보낸다.")
class OrderConfirmedSendFailureIntegrationTest {

    private static final String TOPIC = "order-events";
    private static final String RAW_MESSAGE = "raw-failure-message-not-for-log";
    private static final long PRICE = 2_000L;
    private static final long STOCK = 5L;
    private static final long QUANTITY = 2L;
    private static final Instant T0 = Instant.parse("2026-11-05T05:00:00.000001Z");
    private static final SettableClock OUTBOX_CLOCK = new SettableClock(T0, ZoneOffset.UTC);

    @MockitoBean(name = OrderOutboxKafkaConfig.ORDER_OUTBOX_KAFKA_TEMPLATE, enforceOverride = true)
    private KafkaTemplate<String, String> template;
    @TestBean(name = OrderOutboxClockConfig.OUTBOX_RECORD_CLOCK, methodName = "outboxClock")
    private Clock outboxClock;

    @Autowired private OrderOutboxSender sender;
    @Autowired private OrderOutboxOperations operations;
    @Autowired private OrderOutboxRelay relay;
    @Autowired private PgFixtureServer pg;
    @Autowired private OrderFacade orders;
    @Autowired private OrderConfirmFacade confirmFacade;
    @Autowired private UserFixture users;
    @Autowired private ProductFixture products;
    @Autowired private PointFacade points;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DatabaseCleanUp cleanup;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Long user;
    private ProductModel product;
    private Long order;

    static Clock outboxClock() {
        return OUTBOX_CLOCK;
    }

    @BeforeEach
    void prepare() {
        OUTBOX_CLOCK.set(T0, ZoneOffset.UTC);
        pg.reset();
        user = users.createUserWithPoint().getId();
        points.charge(user, 10_000L);
        product = products.createProduct("발행 실패 상품", PRICE, STOCK);
        order = orders.create(user, List.of(new OrderItemCommand(product.getId(), QUANTITY))).id();
        pg.enqueuePlan(PgOperation.CREATE, PaymentModel.providerOrderIdOf(order), ResponsePlan.outcome(CreateOutcome.SUCCESS));
    }

    @AfterEach
    void cleanUp() throws InterruptedException {
        worker.shutdownNow();
        assertThat(worker.awaitTermination(15, TimeUnit.SECONDS)).as("테스트 소유 worker 종료").isTrue();
        await().atMost(Duration.ofSeconds(5)).until(() ->
            pg.state().activeRequests() == 0 && pg.state().activeCallbackJobs() == 0);
        cleanup.truncateAllTables();
    }

    @DisplayName("send 호출이 예외를 던지면 확정·결제·시각·예약 소비는 그대로, SEND_CALL_FAILED 로 PENDING·+5초이고, 그 전에는 다시 보내지 않으며 그 뒤 같은 원문으로 발행한다.")
    @Test
    void sendCallFailureKeepsConfirmationAndRetriesSameRow(CapturedOutput output) {
        PaymentApplyResult result = confirmFacade.confirm(user, order, PgCommands.forOrder(order));
        Map<String, Object> afterConfirm = committedFacts();
        Map<String, Object> recorded = outbox();
        long createsAfterConfirm = pgCreates();
        when(template.send(anyString(), anyString(), anyString())).thenThrow(new IllegalStateException(RAW_MESSAGE));

        Result<RowResult> failed = operations.runEvent(eventId());
        Map<String, Object> afterFailure = outbox();
        Result<List<RowResult>> early = relay.runDueBatch();
        // 예외를 던지도록 stub 된 mock 이라 when(...) 대신 doReturn 으로 바꾼다(재stub 호출이 예외를 던지지 않게).
        doReturn(CompletableFuture.completedFuture(success())).when(template).send(anyString(), anyString(), anyString());
        OUTBOX_CLOCK.set(T0.plusSeconds(5), ZoneOffset.UTC);
        Result<List<RowResult>> retried = relay.runDueBatch();

        assertConfirmed(result, afterConfirm);
        assertAll(
            () -> assertThat(ReflectionTestUtils.getField(sender, "template")).as("실제 W7 sender 가 대역 template 을 씀").isSameAs(template),
            () -> assertThat(recorded).containsEntry("status", "PENDING").containsEntry("attempt_count", 0L),
            () -> assertThat(failed.value()).map(RowResult::outcome).contains(RowOutcome.RETRY_SCHEDULED),
            () -> assertThat(afterFailure).containsEntry("status", "PENDING").containsEntry("attempt_count", 1L)
                .containsEntry("last_error", "SEND_CALL_FAILED:java.lang.IllegalStateException")
                .containsEntry("next_attempt_at", wall(T0.plusSeconds(5))).containsEntry("published_at", null),
            () -> assertThat(early.value()).as("다음 시각 전에는 보내지 않음").contains(List.of()),
            () -> assertThat(retried.value().orElseThrow()).extracting(RowResult::outcome).containsExactly(RowOutcome.PUBLISHED),
            () -> assertThat(sentPayloads(2)).as("두 시도 모두 저장 원문 그대로").containsOnly((String) recorded.get("payload")),
            () -> assertThat(outbox()).containsEntry("status", "PUBLISHED").containsEntry("attempt_count", 2L).containsEntry("last_error", null),
            () -> assertThat(committedFacts()).as("발행 실패·재시도 뒤 새 조회").isEqualTo(afterConfirm),
            () -> assertThat(createsAfterConfirm).as("PG fixture create 수신").isEqualTo(1L),
            () -> assertThat(pgCreates()).as("발행 실패·재시도가 PG 를 다시 부르지 않음").isEqualTo(createsAfterConfirm)
        );
        assertSafeFailureLog(output, "SEND_CALL_FAILED:java.lang.IllegalStateException");
    }

    @DisplayName("send 가 돌려준 Future 가 확정 commit 뒤에 실패하면 완료 전에는 결과가 없고, 실패 뒤 SEND_COMPLETION_FAILED 로 PENDING 이며 확정·PG 는 그대로다.")
    @Test
    void futureFailureKeepsConfirmation(CapturedOutput output) throws Exception {
        PaymentApplyResult result = confirmFacade.confirm(user, order, PgCommands.forOrder(order));
        Map<String, Object> afterConfirm = committedFacts();
        long createsAfterConfirm = pgCreates();
        CompletableFuture<SendResult<String, String>> pending = new CompletableFuture<>();
        when(template.send(anyString(), anyString(), anyString())).thenReturn(pending);

        Future<Result<RowResult>> running = worker.submit(() -> operations.runEvent(eventId()));
        await().atMost(Duration.ofSeconds(10)).until(() -> sendCalls() == 1);
        Map<String, Object> whileWaiting = outbox();
        boolean resultLoggedBeforeCompletion = output.getAll().contains("ORDER_OUTBOX_SEND_FAILED");
        pending.completeExceptionally(new KafkaException(RAW_MESSAGE, new TimeoutException(RAW_MESSAGE)));
        Result<RowResult> failed = running.get(10, TimeUnit.SECONDS);

        assertConfirmed(result, afterConfirm);
        assertAll(
            () -> assertThat(whileWaiting).as("Future 완료 전: 시도만 commit, 결과 없음").containsEntry("status", "PENDING")
                .containsEntry("attempt_count", 1L).containsEntry("last_error", null),
            () -> assertThat(resultLoggedBeforeCompletion).isFalse(),
            () -> assertThat(failed.value()).map(RowResult::outcome).contains(RowOutcome.RETRY_SCHEDULED),
            () -> assertThat(outbox()).containsEntry("status", "PENDING").containsEntry("attempt_count", 1L)
                .containsEntry("last_error", "SEND_COMPLETION_FAILED:org.springframework.kafka.KafkaException")
                .containsEntry("next_attempt_at", wall(T0.plusSeconds(5))),
            () -> assertThat(sentPayloads(1)).containsOnly((String) outbox().get("payload")),
            () -> assertThat(committedFacts()).as("Future 실패 뒤 새 조회").isEqualTo(afterConfirm),
            () -> assertThat(pgCreates()).isEqualTo(createsAfterConfirm).isEqualTo(1L)
        );
        assertSafeFailureLog(output, "SEND_COMPLETION_FAILED:org.springframework.kafka.KafkaException");
    }

    // ---------------------------------------------------------------- 도구

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

    /** relay 서비스 로그는 안전 code·원인 클래스만 남기고 원문 message·stacktrace·payload 를 남기지 않는다. 시도마다 한 줄이다. */
    private void assertSafeFailureLog(CapturedOutput output, String error) {
        String line = "ORDER_OUTBOX_SEND_FAILED outboxId=" + outbox().get("id") + " eventId=" + eventId() + " attemptNo=1 cycleAttemptCount=1 error="
            + error + " status=PENDING transition=APPLIED";
        assertAll(
            () -> assertThat(output.getAll()).contains(line),
            () -> assertThat(output.getAll().lines().filter(each -> each.contains("ORDER_OUTBOX_SEND_FAILED"))).as("한 번").hasSize(1),
            () -> assertThat(output.getAll()).as("원문 예외 메시지").doesNotContain(RAW_MESSAGE),
            () -> assertThat(output.getAll()).as("이 테스트에서 만든 예외의 stacktrace").doesNotContain("at " + getClass().getName()),
            () -> assertThat(output.getAll()).as("payload").doesNotContain("schemaVersion")
        );
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

    private Map<String, Object> outbox() {
        return jdbc.queryForMap("SELECT id, status, attempt_count, next_attempt_at, last_error, published_at, payload"
            + " FROM order_confirmed_outbox WHERE order_id = ?", order);
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

    /** partition 없는 overload 로 설정 topic·주문 key 에 보낸 값들. */
    private List<String> sentPayloads(int times) {
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(template, times(times)).send(eq(TOPIC), eq(Long.toString(order)), payload.capture());
        return payload.getAllValues();
    }

    private SendResult<String, String> success() {
        return new SendResult<>(new ProducerRecord<>(TOPIC, Long.toString(order), "sent"),
            new RecordMetadata(new TopicPartition(TOPIC, 0), 7L, 0, 0L, 2, 10));
    }

    private String eventId() {
        return "order-confirmed:" + order;
    }

    private static LocalDateTime wall(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
