package com.loopers.application.order;

import com.loopers.application.order.OrderOutboxExecutionGate.Outcome;
import com.loopers.application.order.OrderOutboxExecutionGate.Result;
import com.loopers.application.order.OrderOutboxOperations.RecoveryFailedException;
import com.loopers.application.order.OrderOutboxOperations.RecoveryItem;
import com.loopers.application.order.OrderOutboxOperations.RecoveryOutcome;
import com.loopers.application.order.OrderOutboxOperations.RecoveryReport;
import com.loopers.application.payment.PaymentTransactionService;
import com.loopers.application.payment.PaymentTxFixture;
import com.loopers.config.OrderOutboxClockConfig;
import com.loopers.domain.order.OrderConfirmed;
import com.loopers.domain.order.OrderOutboxAttempt;
import com.loopers.domain.order.OrderOutboxMessageVerifier;
import com.loopers.domain.order.OrderOutboxReceipt;
import com.loopers.domain.order.OrderOutboxSender;
import com.loopers.domain.order.OrderOutboxStatus;
import com.loopers.domain.order.OrderOutboxStoredMessage;
import com.loopers.domain.payment.ObservationSource;
import com.loopers.domain.payment.PaymentCheckToken;
import com.loopers.domain.payment.PaymentModel;
import com.loopers.domain.payment.PaymentObservation;
import com.loopers.domain.payment.PgTransactionStatus;
import com.loopers.domain.product.ProductModel;
import com.loopers.fixture.PgTestSupport;
import com.loopers.fixture.SettableClock;
import com.loopers.fixture.pg.RequestCounts;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * O12·O10(복구 부분) 실제 MySQL 통합(설계 W7.3-6). 정상 W7 컨텍스트·자동 relay OFF·테스트 부모 TX 없이 명시 복구를 부르고, 결과는 새 SQL 로 본다.
 *
 * <p>Outbox 가 없는 확정 주문은 실제 결제 TX(최초 CONFIRM)에서 그 주문의 recorder 호출만 건너뛰어 만든다(spy). 확정·결제·예약은 실제 흐름이다.
 * 잘못된 저장 상태·본문은 SQL fixture 로 만들며 거절 검증에만 쓴다(O11 준비의 근거가 아니다). 로컬 PG fixture 를 연결해 복구 중 PG 요청이 0 인지 본다.
 * 기존 PUBLISHED·BLOCKED 는 통제 sender 로 실제 relay 를 돌려 만든다. 이 컨텍스트만 회차 한도를 1 로 두어 실패 한 번에 BLOCKED 가 되게 했다.
 */
@SpringBootTest(properties = "commerce.order-events.relay.max-cycle-attempts=1")
@Timeout(120)
@DisplayName("명시 주문 복구는 없는 Outbox 만 저장 사실로 되살리고, 기존 행은 같은 사실인지만 확인하며, 실패·중단은 commit 된 대상과 미시도를 구분해 보고한다.")
class OrderOutboxRecoveryIntegrationTest extends PgTestSupport {

    private static final Long USER = 1L;
    private static final Instant NOW = Instant.parse("2026-11-05T03:00:00.000001Z");
    private static final SettableClock CLOCK = new SettableClock(NOW, ZoneOffset.UTC);
    private static final ControlledSender SENDER = new ControlledSender();
    private static final String OUTBOX_ROW = "SELECT id, event_id, order_id, event_type, schema_version, message_key, payload, status,"
        + " attempt_count, cycle_attempt_count, next_attempt_at, last_error, published_at, created_at, updated_at, deleted_at"
        + " FROM order_confirmed_outbox WHERE order_id = ?";

    @TestBean(name = OrderOutboxClockConfig.OUTBOX_RECORD_CLOCK, methodName = "outboxClock")
    private Clock outboxClock;
    @TestBean(methodName = "sender")
    private OrderOutboxSender sender;
    @MockitoSpyBean
    private OrderConfirmedRecorder recorder;

    @Autowired private OrderOutboxOperations operations;
    @Autowired private OrderOutboxRelay relay;
    @Autowired private OrderOutboxRecoveryTransactionService recoveryTransactions;
    @Autowired private OrderOutboxMessageVerifier verifier;
    @Autowired private OrderOutboxExecutionGate gate;
    @Autowired private PaymentTransactionService payments;
    @Autowired private PaymentTxFixture fixture;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DatabaseCleanUp databaseCleanUp;

    private final ExecutorService workers = Executors.newFixedThreadPool(2);

    static Clock outboxClock() {
        return CLOCK;
    }

    static OrderOutboxSender sender() {
        return SENDER;
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        workers.shutdownNow();
        assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).as("작업 thread 종료").isTrue();
        SENDER.fail = false;
        CLOCK.set(NOW, ZoneOffset.UTC);
        databaseCleanUp.truncateAllTables();
    }

    // ---------------------------------------------------------------- 복구·EXISTS

    @DisplayName("없는 Outbox 는 저장 주문의 같은 사실로 PENDING 초깃값을 넣고, 중복·순서 섞인 목록은 오름차순 한 번씩이며, 다시 실행하면 EXISTS 로 아무것도 바꾸지 않는다.")
    @Test
    void restoresMissingRowsThenReportsExists() {
        Missing a = confirmWithoutOutbox();
        Missing b = confirmWithoutOutbox();
        Long recorded = confirm();
        Map<String, Object> recordedRow = outboxRow(recorded);
        Map<Long, Map<String, Object>> business = business(a.orderId(), b.orderId(), recorded);
        RequestCounts pgBefore = pg.state().counts();

        Result<RecoveryReport> first = operations.recoverOrders(List.of(b.orderId(), recorded, a.orderId(), b.orderId()));
        Map<String, Object> restoredA = outboxRow(a.orderId());
        Map<String, Object> restoredB = outboxRow(b.orderId());
        Result<RecoveryReport> second = operations.recoverOrders(List.of(a.orderId(), b.orderId()));

        assertAll(
            () -> assertThat(first.outcome()).isEqualTo(Outcome.EXECUTED),
            () -> assertThat(first.value().orElseThrow().items()).containsExactly(
                item(a.orderId(), RecoveryOutcome.RESTORED, OrderOutboxStatus.PENDING),
                item(b.orderId(), RecoveryOutcome.RESTORED, OrderOutboxStatus.PENDING),
                item(recorded, RecoveryOutcome.EXISTS, OrderOutboxStatus.PENDING)),
            () -> assertThat(List.of(restoredA, restoredB)).allSatisfy(row -> assertThat(row).containsEntry("status", "PENDING")
                .containsEntry("attempt_count", 0L).containsEntry("cycle_attempt_count", 0)
                .containsEntry("next_attempt_at", wall(NOW)).containsEntry("last_error", null).containsEntry("published_at", null)
                .containsEntry("event_type", "OrderConfirmed").containsEntry("schema_version", 1)),
            () -> assertThat(restoredA).containsEntry("event_id", "order-confirmed:" + a.orderId())
                .containsEntry("message_key", a.orderId().toString()),
            () -> assertThat(verifier.parseAndValidate(stored(restoredA))).as("최초 확정 TX 가 기록하려던 사건과 같은 사실").isEqualTo(a.event()),
            () -> assertThat(verifier.parseAndValidate(stored(restoredB))).isEqualTo(b.event()),
            () -> assertThat(outboxRow(recorded)).as("기존 PENDING 전체 열 불변").isEqualTo(recordedRow),
            () -> assertThat(second.value().orElseThrow().items()).containsExactly(
                item(a.orderId(), RecoveryOutcome.EXISTS, OrderOutboxStatus.PENDING),
                item(b.orderId(), RecoveryOutcome.EXISTS, OrderOutboxStatus.PENDING)),
            () -> assertThat(outboxRow(a.orderId())).as("두 번째 복구는 불변").isEqualTo(restoredA),
            () -> assertThat(outboxRow(b.orderId())).isEqualTo(restoredB),
            () -> assertThat(business(a.orderId(), b.orderId(), recorded)).as("주문·결제·예약·재고·충돌 불변").isEqualTo(business),
            () -> assertThat(pg.state().counts()).as("복구 중 PG 요청 없음").isEqualTo(pgBefore)
        );
    }

    @DisplayName("저장된 SUCCESS 의 최근 확인만 UNAVAILABLE·CONFLICT 인 주문도 복구하고 결제 상태는 그대로다.")
    @Test
    void recentVerificationDoesNotCancelSuccess() {
        Missing unavailable = confirmWithoutOutbox();
        payments.applyObservation(verifyToken(unavailable.orderId()), new PaymentObservation.Unavailable(ObservationSource.QUERY));
        Missing conflicted = confirmWithoutOutbox();
        payments.applyObservation(verifyToken(conflicted.orderId()), new PaymentObservation.TransactionObserved(ObservationSource.QUERY,
            "tx-" + conflicted.orderId(), PaymentModel.providerOrderIdOf(conflicted.orderId()), 1L, PgTransactionStatus.SUCCESS));
        Map<String, Object> unavailablePayment = fixture.payment(unavailable.orderId());
        Map<String, Object> conflictedPayment = fixture.payment(conflicted.orderId());

        Result<RecoveryReport> result = operations.recoverOrders(List.of(unavailable.orderId(), conflicted.orderId()));

        assertAll(
            () -> assertThat(verification(unavailable.orderId())).isEqualTo(List.of("SUCCESS", "UNAVAILABLE")),
            () -> assertThat(verification(conflicted.orderId())).isEqualTo(List.of("SUCCESS", "CONFLICT")),
            () -> assertThat(result.value().orElseThrow().items()).extracting(RecoveryItem::outcome)
                .containsExactly(RecoveryOutcome.RESTORED, RecoveryOutcome.RESTORED),
            () -> assertThat(fixture.payment(unavailable.orderId())).isEqualTo(unavailablePayment),
            () -> assertThat(fixture.payment(conflicted.orderId())).isEqualTo(conflictedPayment)
        );
    }

    @DisplayName("유효한 기존 PENDING·PUBLISHED·BLOCKED 는 EXISTS 와 실제 상태를 돌려주고 원문·횟수·시각·오류·감사 시각을 바꾸지 않는다.")
    @Test
    void existingStatesAreUntouched() {
        Long pending = confirm();
        Long published = confirm();
        Long blocked = confirm();
        assertThat(operations.runEvent("order-confirmed:" + published).value().orElseThrow().outcome())
            .isEqualTo(OrderOutboxRelay.RowOutcome.PUBLISHED);
        SENDER.fail = true;
        assertThat(operations.runEvent("order-confirmed:" + blocked).value().orElseThrow().outcome())
            .isEqualTo(OrderOutboxRelay.RowOutcome.BLOCKED);
        SENDER.fail = false;
        List<Map<String, Object>> before = List.of(outboxRow(pending), outboxRow(published), outboxRow(blocked));
        int sendsBefore = SENDER.calls;

        Result<RecoveryReport> result = operations.recoverOrders(List.of(pending, published, blocked));

        assertAll(
            () -> assertThat(result.value().orElseThrow().items()).containsExactly(
                item(pending, RecoveryOutcome.EXISTS, OrderOutboxStatus.PENDING),
                item(published, RecoveryOutcome.EXISTS, OrderOutboxStatus.PUBLISHED),
                item(blocked, RecoveryOutcome.EXISTS, OrderOutboxStatus.BLOCKED)),
            () -> assertThat(before.get(2)).containsEntry("status", "BLOCKED").containsEntry("attempt_count", 1L),
            () -> assertThat(List.of(outboxRow(pending), outboxRow(published), outboxRow(blocked))).isEqualTo(before),
            () -> assertThat(SENDER.calls).as("복구는 send 하지 않음").isEqualTo(sendsBefore)
        );
    }

    @DisplayName("기존 행의 JSON 표현만 달라도 같은 사실이면 EXISTS 이고, 수량이 다른 사실이면 OUTBOX_FACT_MISMATCH 로 실패하며 행을 고치지 않는다.")
    @Test
    void comparesStoredFactByMeaning() {
        Long same = confirm();
        Long conflicting = confirm();
        String samePayload = (String) outboxRow(same).get("payload");
        // 저장 원문과 문자열이 다른 같은 사실: 앞에 미사용 속성을 더했다(O12 fixture).
        String reordered = "{\"note\":\"x\"," + samePayload.substring(1, samePayload.indexOf(",\"items\""))
            + "," + samePayload.substring(samePayload.indexOf("\"items\""));
        jdbc.update("UPDATE order_confirmed_outbox SET payload = ? WHERE order_id = ?", reordered, same);
        String conflictPayload = ((String) outboxRow(conflicting).get("payload")).replace("\"quantity\":2", "\"quantity\":3");
        jdbc.update("UPDATE order_confirmed_outbox SET payload = ? WHERE order_id = ?", conflictPayload, conflicting);
        Map<String, Object> sameRow = outboxRow(same);
        Map<String, Object> conflictRow = outboxRow(conflicting);

        Result<RecoveryReport> equivalent = operations.recoverOrders(List.of(same));

        assertAll(
            () -> assertThat(reordered).isNotEqualTo(samePayload),
            () -> assertThat(equivalent.value().orElseThrow().items()).containsExactly(item(same, RecoveryOutcome.EXISTS, OrderOutboxStatus.PENDING)),
            () -> assertThat(outboxRow(same)).isEqualTo(sameRow),
            () -> assertThatThrownBy(() -> operations.recoverOrders(List.of(conflicting)))
                .isInstanceOfSatisfying(RecoveryFailedException.class, failure -> {
                    assertThat(failure.failureCode()).isEqualTo("OUTBOX_FACT_MISMATCH");
                    assertThat(failure.failedOrderId()).contains(conflicting);
                    assertThat(failure.getMessage()).doesNotContain("quantity");
                }),
            () -> assertThat(outboxRow(conflicting)).isEqualTo(conflictRow)
        );
    }

    @DisplayName("원본 부족·거래 키 없음·금액·품목·시각 불일치·미확정·POINT·없는 주문은 거절하고 Outbox 를 만들지 않는다.")
    @Test
    void rejectsInvalidStoredStates() {
        Map<String, String> fixtures = Map.of(
            "거래 키 없음", "UPDATE payment SET transaction_key = NULL WHERE order_id = ?",
            "결제액 불일치", "UPDATE orders SET paid_amount = paid_amount + 1 WHERE id = ?",
            "품목 수량 변경", "UPDATE order_item SET quantity = quantity + 1 WHERE order_id = ?",
            "확정 시각 없음", "UPDATE orders SET confirmed_at = NULL WHERE id = ?",
            "거래 연결 불일치", "UPDATE payment SET provider_order_id = 'LP-ORD-9999999999' WHERE order_id = ?");
        List<String> rejected = new ArrayList<>();
        fixtures.forEach((name, sql) -> {
            Long order = confirmWithoutOutbox().orderId();
            jdbc.update(sql, order);
            assertRejected(name, order, "INTERNAL_ERROR");
            rejected.add(name);
        });
        Long pending = fixture.draftOrder(USER, 2L, fixture.product(1_000L, 10L));
        payments.beginConfirmation(USER, pending, PaymentTxFixture.command(UUID.randomUUID().toString()));
        assertRejected("PG 결과 대기 주문", pending, "INTERNAL_ERROR");
        Long point = fixture.draftOrder(USER, 2L, fixture.product(1_000L, 10L));
        jdbc.update("UPDATE orders SET status = 'CONFIRMED', payment_method = 'POINT', point_amount = 3000, paid_amount = 3000 WHERE id = ?",
            point);
        assertRejected("과거 POINT 주문", point, "INTERNAL_ERROR");
        assertRejected("없는 주문", 999_999L, "ORDER_NOT_FOUND");
        assertThat(rejected).hasSize(5);
    }

    // ---------------------------------------------------------------- 부분 실패·입력·gate

    @DisplayName("A commit 뒤 B 가 실패하면 C 는 미시도이고 A 는 남으며, 다시 실행해도 A 는 EXISTS 로 그대로다.")
    @Test
    void partialFailureKeepsCommittedTargets() {
        Long a = confirmWithoutOutbox().orderId();
        Long b = confirmWithoutOutbox().orderId();
        Long c = confirmWithoutOutbox().orderId();
        jdbc.update("UPDATE payment SET transaction_key = NULL WHERE order_id = ?", b);

        RecoveryFailedException first = catchRecovery(() -> operations.recoverOrders(List.of(c, a, b)));
        Map<String, Object> restoredA = outboxRow(a);
        RecoveryFailedException again = catchRecovery(() -> operations.recoverOrders(List.of(a, b, c)));

        assertAll(
            () -> assertThat(first.completed()).containsExactly(item(a, RecoveryOutcome.RESTORED, OrderOutboxStatus.PENDING)),
            () -> assertThat(first.failedOrderId()).contains(b),
            () -> assertThat(first.failureCode()).isEqualTo("INTERNAL_ERROR"),
            () -> assertThat(first.unattempted()).containsExactly(c),
            () -> assertThat(first.getCause()).isNotNull(),
            () -> assertThat(fixture.outboxCount(b)).isZero(),
            () -> assertThat(fixture.outboxCount(c)).as("미시도").isZero(),
            () -> assertThat(again.completed()).containsExactly(item(a, RecoveryOutcome.EXISTS, OrderOutboxStatus.PENDING)),
            () -> assertThat(outboxRow(a)).isEqualTo(restoredA),
            () -> assertThat(again.unattempted()).containsExactly(c)
        );
    }

    @DisplayName("빈·비양수·null 목록과 상위 TX 는 gate 전에 거절하고, gate 가 바쁘거나 멈췄으면 DB 를 바꾸지 않고 값 없는 BUSY·STOPPING 이다.")
    @Test
    void rejectsInputAndRespectsGate() throws Exception {
        Long missing = confirmWithoutOutbox().orderId();
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<Result<Void>> holder = workers.submit(() -> gate.tryRun(() -> {
            held.countDown();
            awaitLatch(release);
        }));
        assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
        Result<RecoveryReport> busy = operations.recoverOrders(List.of(missing));
        release.countDown();
        holder.get(10, TimeUnit.SECONDS);
        OrderOutboxExecutionGate stoppedGate = new OrderOutboxExecutionGate();
        stoppedGate.stop();
        Result<RecoveryReport> stopping = new OrderOutboxOperations(relay, recoveryTransactions, stoppedGate, CLOCK)
            .recoverOrders(List.of(missing));

        assertAll(
            () -> assertThat(busy).isEqualTo(new Result<RecoveryReport>(Outcome.BUSY, Optional.empty())),
            () -> assertThat(stopping).isEqualTo(new Result<RecoveryReport>(Outcome.STOPPING, Optional.empty())),
            () -> assertThat(fixture.outboxCount(missing)).as("BUSY·STOPPING 은 DB 를 바꾸지 않음").isZero(),
            () -> assertThat(List.of(List.<Long>of(), List.of(0L), List.of(-1L), Arrays.asList(missing, null)))
                .allSatisfy(input -> assertThatThrownBy(() -> operations.recoverOrders(input)).isInstanceOf(IllegalArgumentException.class)),
            () -> assertThatThrownBy(() -> operations.recoverOrders(null)).isInstanceOf(IllegalArgumentException.class),
            () -> assertThatThrownBy(() -> new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> operations.recoverOrders(List.of(missing)))).isInstanceOf(IllegalStateException.class),
            () -> assertThat(fixture.outboxCount(missing)).isZero()
        );
    }

    // ---------------------------------------------------------------- 종료 경계(O10 복구 부분)

    @DisplayName("첫 TX 전에 interrupt 가 있으면 TX 를 하나도 부르지 않고 전체를 미시도로 보고하며 플래그를 지우지 않는다.")
    @Test
    void interruptBeforeFirstTransaction() throws Exception {
        Long a = confirmWithoutOutbox().orderId();
        Long b = confirmWithoutOutbox().orderId();
        OrderOutboxRecoveryTransactionService observed = mock(OrderOutboxRecoveryTransactionService.class, delegatesTo(recoveryTransactions));
        OrderOutboxOperations own = new OrderOutboxOperations(relay, observed, new OrderOutboxExecutionGate(), CLOCK);

        Object[] finished = workers.submit(() -> {
            Thread.currentThread().interrupt();
            Throwable thrown = catchRecovery(() -> own.recoverOrders(List.of(b, a)));
            return new Object[] {thrown, Thread.currentThread().isInterrupted()};
        }).get(10, TimeUnit.SECONDS);
        RecoveryFailedException failure = (RecoveryFailedException) finished[0];

        assertAll(
            () -> assertThat(failure.failureCode()).isEqualTo("RECOVERY_INTERRUPTED"),
            () -> assertThat(failure.failedOrderId()).isEmpty(),
            () -> assertThat(failure.completed()).isEmpty(),
            () -> assertThat(failure.unattempted()).containsExactly(a, b),
            () -> assertThat(finished[1]).as("interrupt 플래그 유지").isEqualTo(true),
            () -> verify(observed, never()).recoverOne(anyLong()),
            () -> assertThat(fixture.outboxCount(a) + fixture.outboxCount(b)).isZero()
        );
    }

    @DisplayName("첫 대상의 commit 반환 뒤 장벽에서 종료를 관측하면 다음 TX 없이 첫 대상만 completed, 실패 대상 없음, 나머지는 미시도다.")
    @Test
    void stopAfterFirstCommitLeavesRestUnattempted() throws Exception {
        Long a = confirmWithoutOutbox().orderId();
        Long b = confirmWithoutOutbox().orderId();
        Long c = confirmWithoutOutbox().orderId();
        OrderOutboxExecutionGate ownGate = new OrderOutboxExecutionGate();
        OrderOutboxRecoveryTransactionService observed = mock(OrderOutboxRecoveryTransactionService.class, delegatesTo(recoveryTransactions));
        CountDownLatch committed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean barrierInterrupted = new AtomicBoolean();
        doAnswer(invocation -> {
            RecoveryItem item = recoveryTransactions.recoverOne(invocation.getArgument(0)); // 실제 TX 프록시: 여기서 commit 이 끝났다.
            committed.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("장벽이 풀리지 않았습니다.");
                }
            } catch (InterruptedException interrupted) {
                barrierInterrupted.set(true);
                Thread.currentThread().interrupt(); // 장벽 자체의 interrupt 를 실패로 던지지 않고 다음 대상 전 검사에 넘긴다.
            }
            return item;
        }).when(observed).recoverOne(anyLong());
        OrderOutboxOperations own = new OrderOutboxOperations(relay, observed, ownGate, CLOCK);

        Future<Throwable> running = workers.submit(() -> catchRecovery(() -> own.recoverOrders(List.of(a, b, c))));
        assertThat(committed.await(10, TimeUnit.SECONDS)).isTrue();
        long visibleBeforeStop = fixture.outboxCount(a);
        ownGate.stop();
        RecoveryFailedException failure = (RecoveryFailedException) running.get(10, TimeUnit.SECONDS);
        release.countDown();

        assertAll(
            () -> assertThat(visibleBeforeStop).as("장벽 시점에 첫 대상 commit 이 새 연결에서 보임").isEqualTo(1L),
            () -> assertThat(barrierInterrupted).as("stop 이 진행 thread 에 interrupt 전달").isTrue(),
            () -> assertThat(failure.completed()).containsExactly(item(a, RecoveryOutcome.RESTORED, OrderOutboxStatus.PENDING)),
            () -> assertThat(failure.failedOrderId()).isEmpty(),
            () -> assertThat(failure.failureCode()).isEqualTo("RECOVERY_INTERRUPTED"),
            () -> assertThat(failure.unattempted()).containsExactly(b, c),
            () -> verify(observed, times(1)).recoverOne(anyLong()),
            () -> assertThat(fixture.outboxCount(b) + fixture.outboxCount(c)).isZero(),
            () -> assertThat(ownGate.awaitIdle(java.time.Duration.ofSeconds(5))).as("gate finally 해제").isTrue()
        );
    }

    @DisplayName("복구 TX 의 첫 업무 SQL 은 Order 잠금이라 같은 주문을 잠근 TX 가 끝날 때까지 Outbox 에 손대지 않고 기다린다.")
    @Test
    void waitsForOrderLockBeforeOutbox() throws Exception {
        Long a = confirmWithoutOutbox().orderId();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> lockHolder = workers.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT id FROM orders WHERE id = ? FOR UPDATE", Long.class, a);
            locked.countDown();
            awaitLatch(release);
        }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        Future<Result<RecoveryReport>> recovering = workers.submit(() -> operations.recoverOrders(List.of(a)));

        await().atMost(java.time.Duration.ofSeconds(10)).until(fixture::someoneWaitsForOrderLock);
        long outboxWhileWaiting = fixture.outboxCount(a);
        release.countDown();
        lockHolder.get(10, TimeUnit.SECONDS);
        Result<RecoveryReport> result = recovering.get(10, TimeUnit.SECONDS);

        assertAll(
            () -> assertThat(outboxWhileWaiting).as("Order 잠금 대기 중 Outbox 미접근").isZero(),
            () -> assertThat(result.value().orElseThrow().items()).containsExactly(item(a, RecoveryOutcome.RESTORED, OrderOutboxStatus.PENDING))
        );
    }

    // ---------------------------------------------------------------- 도구

    private record Missing(Long orderId, OrderConfirmed event) {
    }

    /** 실제 최초 CONFIRM 으로 확정하되 이 주문의 recorder 호출만 건너뛴다. 결제 TX 가 기록하려던 사건을 함께 돌려준다. */
    private Missing confirmWithoutOutbox() {
        Long orderId = newOrder();
        // spy 는 TX 프록시 안쪽 대상이다. 프록시를 거쳐 stub 하면 MANDATORY 검사에 걸리므로 대상에 직접 stub·verify 한다.
        OrderConfirmedRecorder spy = AopTestUtils.getUltimateTargetObject(recorder);
        doNothing().when(spy).record(argThat(event -> event != null && event.orderId() == orderId));
        applySuccess(orderId);
        ArgumentCaptor<OrderConfirmed> captor = ArgumentCaptor.forClass(OrderConfirmed.class);
        verify(spy, org.mockito.Mockito.atLeastOnce()).record(captor.capture());
        OrderConfirmed event = captor.getAllValues().stream().filter(value -> value.orderId() == orderId).findFirst().orElseThrow();
        assertThat(fixture.outboxCount(orderId)).as("준비: Outbox 없는 확정").isZero();
        assertThat(fixture.order(orderId)).containsEntry("status", "CONFIRMED");
        return new Missing(orderId, event);
    }

    /** 실제 최초 CONFIRM 과 같은 TX 의 Outbox 기록. */
    private Long confirm() {
        Long orderId = newOrder();
        applySuccess(orderId);
        assertThat(fixture.outboxCount(orderId)).isEqualTo(1L);
        return orderId;
    }

    private Long newOrder() {
        ProductModel first = fixture.product(1_000L, 10L);
        ProductModel second = fixture.product(500L, 10L);
        return fixture.draftOrder(USER, 2L, first, second);
    }

    private void applySuccess(Long orderId) {
        PaymentCheckToken token = payments.beginConfirmation(USER, orderId, PaymentTxFixture.command(UUID.randomUUID().toString())).token();
        payments.applyObservation(token, new PaymentObservation.TransactionObserved(ObservationSource.CREATE, "tx-" + orderId,
            PaymentModel.providerOrderIdOf(orderId), 3_000L, PgTransactionStatus.SUCCESS));
    }

    private PaymentCheckToken verifyToken(Long orderId) {
        return payments.prepareVerification(orderId, USER, null).token();
    }

    private void assertRejected(String name, Long orderId, String code) {
        RecoveryFailedException failure = catchRecovery(() -> operations.recoverOrders(List.of(orderId)));
        assertAll(name,
            () -> assertThat(failure.failureCode()).isEqualTo(code),
            () -> assertThat(failure.failedOrderId()).contains(orderId),
            () -> assertThat(failure.completed()).isEmpty(),
            () -> assertThat(fixture.outboxCount(orderId)).isZero()
        );
    }

    private static RecoveryFailedException catchRecovery(Runnable call) {
        try {
            call.run();
        } catch (RecoveryFailedException failure) {
            return failure;
        }
        throw new AssertionError("RecoveryFailedException 이 나야 합니다.");
    }

    private static RecoveryItem item(Long orderId, RecoveryOutcome outcome, OrderOutboxStatus status) {
        return new RecoveryItem(orderId, "order-confirmed:" + orderId, outcome, status);
    }

    private Map<String, Object> outboxRow(Long orderId) {
        return jdbc.queryForMap(OUTBOX_ROW, orderId);
    }

    private static OrderOutboxStoredMessage stored(Map<String, Object> row) {
        return new OrderOutboxStoredMessage((String) row.get("event_id"), ((Number) row.get("order_id")).longValue(),
            (String) row.get("event_type"), ((Number) row.get("schema_version")).intValue(), (String) row.get("message_key"),
            (String) row.get("payload"));
    }

    private List<String> verification(Long orderId) {
        return jdbc.queryForObject("SELECT status, verification_status FROM payment WHERE order_id = ?",
            (row, index) -> List.of(row.getString(1), row.getString(2)), orderId);
    }

    /** 주문·결제·예약 이력·재고·충돌 요약. 복구 전후에 같아야 한다. */
    private Map<Long, Map<String, Object>> business(Long... orderIds) {
        Map<Long, Map<String, Object>> facts = new java.util.TreeMap<>();
        for (Long orderId : orderIds) {
            facts.put(orderId, Map.of(
                "order", jdbc.queryForMap("SELECT status, paid_amount, CAST(confirmed_at AS CHAR) c, CAST(updated_at AS CHAR) u"
                    + " FROM orders WHERE id = ?", orderId),
                "payment", jdbc.queryForMap("SELECT status, transaction_key, verification_status, CAST(updated_at AS CHAR) u"
                    + " FROM payment WHERE order_id = ?", orderId),
                "histories", jdbc.queryForList("SELECT cause, COUNT(*) n FROM stock_history WHERE order_id = ? GROUP BY cause ORDER BY cause",
                    orderId),
                "stock", jdbc.queryForList("SELECT p.id, p.stock_quantity FROM product p JOIN order_item i ON i.product_id = p.id"
                    + " WHERE i.order_id = ? ORDER BY p.id", orderId),
                "conflicts", jdbc.queryForObject("SELECT COUNT(*) FROM payment_conflict c JOIN payment p ON p.id = c.payment_id"
                    + " WHERE p.order_id = ?", Long.class, orderId)));
        }
        return facts;
    }

    private static LocalDateTime wall(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("장벽이 풀리지 않았습니다.");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    /** 성공 또는 실패로 즉시 완료하는 sender 대역. 실제 broker 가 아니다. */
    static final class ControlledSender implements OrderOutboxSender {

        volatile boolean fail;
        volatile int calls;

        @Override
        public CompletionStage<OrderOutboxReceipt> send(OrderOutboxAttempt attempt) {
            calls++;
            return fail ? CompletableFuture.failedFuture(new IllegalStateException("통제 실패"))
                : CompletableFuture.completedFuture(new OrderOutboxReceipt("order-events-test", 0, calls));
        }
    }
}
