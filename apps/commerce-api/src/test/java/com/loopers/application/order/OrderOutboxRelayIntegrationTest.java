package com.loopers.application.order;

import com.loopers.application.order.OrderOutboxExecutionGate.Outcome;
import com.loopers.application.order.OrderOutboxExecutionGate.Result;
import com.loopers.application.order.OrderOutboxRelay.RowOutcome;
import com.loopers.application.order.OrderOutboxRelay.RowResult;
import com.loopers.config.OrderOutboxClockConfig;
import com.loopers.config.OrderOutboxRelayProperties;
import com.loopers.domain.order.OrderConfirmed;
import com.loopers.domain.order.OrderConfirmedOutboxModel.Transition;
import com.loopers.domain.order.OrderOutboxAttempt;
import com.loopers.domain.order.OrderOutboxMessageException;
import com.loopers.domain.order.OrderOutboxMessageVerifier;
import com.loopers.domain.order.OrderOutboxReceipt;
import com.loopers.domain.order.OrderOutboxSender;
import com.loopers.domain.order.OrderOutboxStoredMessage;
import com.loopers.fixture.SettableClock;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * O8·O9·O10(수동 실행 부분) 실제 MySQL 통합(설계 W7.3-3·W7.3-4·W7.3-7). 정상 W7 컨텍스트의 실제 relay·TX 프록시·verifier·gate 를 쓰고,
 * sender·outboxRecordClock·probe 만 통제 대역으로 바꾼다. 부모 테스트 TX 없이 relay 를 호출하고 결과는 새 SQL 로 확인한다.
 *
 * <p>통제 Future 완료는 실제 broker ACK 가 아니다. 실제 Kafka·consumer·두 중단 수렴은 후속 E2E 범위다. 행 준비는 제품 recorder 로 하고,
 * SQL 로 바꾸는 것은 손상 본문(D)과 DB 실패 trigger(F) fixture 뿐이다. send 대기는 테스트 설정 500ms 로 줄였고 운영 기본값은 그대로다.
 */
@SpringBootTest(properties = "commerce.order-events.relay.send-wait-timeout=500ms")
@Timeout(120)
@DisplayName("수동 relay 는 짧은 TX 로 시도를 commit 하고 TX 밖에서 보낸 뒤 결과만 저장하며, 재시도 예산·차단·재개·단일 실행을 지킨다.")
class OrderOutboxRelayIntegrationTest {

    private static final Instant BASE = Instant.parse("2026-11-04T01:00:00.000001Z");
    private static final SettableClock CLOCK = new SettableClock(BASE, ZoneOffset.UTC);
    private static final ControlledSender SENDER = new ControlledSender();
    private static final ControlledProbe PROBE = new ControlledProbe();
    private static final String SELECT_ROW = "SELECT id, event_id, order_id, event_type, schema_version, message_key, payload, status,"
        + " attempt_count, cycle_attempt_count, next_attempt_at, last_error, published_at, created_at, updated_at, deleted_at"
        + " FROM order_confirmed_outbox WHERE event_id = ?";

    @TestBean(name = OrderOutboxClockConfig.OUTBOX_RECORD_CLOCK, methodName = "outboxClock")
    private Clock outboxClock;
    @TestBean(methodName = "sender")
    private OrderOutboxSender sender;
    @TestBean(methodName = "probe")
    private OrderOutboxRelayProbe probe;

    @Autowired private OrderOutboxRelay relay;
    @Autowired private OrderOutboxOperations operations;
    @Autowired private OrderOutboxRelayTransactionService transactions;
    @Autowired private OrderOutboxMessageVerifier verifier;
    @Autowired private OrderOutboxExecutionGate gate;
    @Autowired private OrderOutboxRelayProperties properties;
    @Autowired private OrderConfirmedRecorder recorder;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DatabaseCleanUp databaseCleanUp;
    @Autowired private ApplicationContext context;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    static Clock outboxClock() {
        return CLOCK;
    }

    static OrderOutboxSender sender() {
        return SENDER;
    }

    static OrderOutboxRelayProbe probe() {
        return PROBE;
    }

    @BeforeEach
    void reset() {
        CLOCK.set(BASE, ZoneOffset.UTC);
        SENDER.reset();
        PROBE.failure = null;
    }

    @AfterEach
    void cleanUp() throws InterruptedException {
        SENDER.releasePending();
        worker.shutdownNow();
        assertThat(worker.awaitTermination(10, TimeUnit.SECONDS)).as("작업 thread 종료").isTrue();
        databaseCleanUp.truncateAllTables();
    }

    // ---------------------------------------------------------------- 성공·짧은 TX

    @DisplayName("성공: 시도 정보가 send 전에 commit 되고 send 대기 중 행 잠금이 없으며, Future 성공 뒤 PUBLISHED·불변 사실·횟수 1/1 이다.")
    @Test
    void publishesWithShortTransactions() throws Exception {
        record(101L);
        Map<String, Object> before = row(101L);
        CompletableFuture<OrderOutboxReceipt> pending = new CompletableFuture<>();
        SENDER.plan(attempt -> pending);

        Future<Result<List<RowResult>>> running = worker.submit(relay::runDueBatch);
        SENDER.awaitCalls(1);
        Map<String, Object> during = new TransactionTemplate(transactionManager).execute(status ->
            jdbc.queryForMap(SELECT_ROW + " FOR UPDATE NOWAIT", "order-confirmed:101"));
        CLOCK.set(BASE.plusSeconds(2), ZoneOffset.UTC);
        pending.complete(new OrderOutboxReceipt("order-events-test", 1, 7L));
        Result<List<RowResult>> result = running.get(10, TimeUnit.SECONDS);
        Map<String, Object> after = row(101L);
        OrderOutboxAttempt sent = SENDER.calls.get(0);

        assertAll(
            () -> assertThat(result.outcome()).isEqualTo(Outcome.EXECUTED),
            () -> assertThat(result.value()).contains(List.of(new RowResult(id(before), RowOutcome.PUBLISHED))),
            () -> assertThat(during).as("send 대기 중 NOWAIT 잠금 성공·시도 commit 완료")
                .containsEntry("status", "PENDING").containsEntry("attempt_count", 1L).containsEntry("cycle_attempt_count", 1)
                .containsEntry("next_attempt_at", wall(BASE.plusSeconds(5))),
            () -> assertThat(sent).isEqualTo(new OrderOutboxAttempt(id(before), 1L, 1, sent.message())),
            () -> assertThat(sent.message().payload()).as("저장 원문 그대로").isEqualTo(before.get("payload")),
            () -> assertThat(sent.message().messageKey()).isEqualTo("101"),
            () -> assertThat(after).containsEntry("status", "PUBLISHED").containsEntry("attempt_count", 1L)
                .containsEntry("cycle_attempt_count", 1).containsEntry("published_at", wall(BASE.plusSeconds(2)))
                .containsEntry("last_error", null).containsEntry("deleted_at", null),
            () -> assertFacts(before, after)
        );
    }

    // ---------------------------------------------------------------- 실패 분류·timeout·늦은 완료

    @DisplayName("동기 호출·비동기 완료 실패·timeout 은 각각 안전 code 로 PENDING 에 저장되고 관측 now 기준 다음 시각이며, 늦은 성공·실패는 행을 바꾸지 않는다.")
    @Test
    void recordsSendFailuresByKind() throws Exception {
        for (long order = 201L; order <= 204L; order++) {
            record(order);
        }
        CompletableFuture<OrderOutboxReceipt> lateSuccess = new CompletableFuture<>();
        CompletableFuture<OrderOutboxReceipt> lateFailure = new CompletableFuture<>();
        SENDER.plan(attempt -> {
            CLOCK.set(BASE.plusSeconds(2), ZoneOffset.UTC);
            throw new IllegalStateException("secret call detail");
        });
        SENDER.plan(attempt -> CompletableFuture.failedFuture(new IllegalArgumentException("secret completion detail")));
        SENDER.plan(attempt -> lateSuccess);
        SENDER.plan(attempt -> lateFailure);

        Result<List<RowResult>> result = relay.runDueBatch();
        List<Map<String, Object>> afterRun = List.of(row(201L), row(202L), row(203L), row(204L));
        lateSuccess.complete(new OrderOutboxReceipt("order-events-test", 0, 9L));
        lateFailure.completeExceptionally(new IllegalStateException("late"));
        List<Map<String, Object>> afterLate = List.of(row(201L), row(202L), row(203L), row(204L));

        assertAll(
            () -> assertThat(result.value().orElseThrow()).extracting(RowResult::outcome).containsOnly(RowOutcome.RETRY_SCHEDULED).hasSize(4),
            () -> assertThat(afterRun.get(0)).containsEntry("last_error", "SEND_CALL_FAILED:java.lang.IllegalStateException")
                .containsEntry("next_attempt_at", wall(BASE.plusSeconds(7))),
            () -> assertThat(afterRun.get(1)).containsEntry("last_error", "SEND_COMPLETION_FAILED:java.lang.IllegalArgumentException"),
            () -> assertThat(afterRun.get(2)).containsEntry("last_error", "SEND_WAIT_TIMEOUT"),
            () -> assertThat(afterRun.get(3)).containsEntry("last_error", "SEND_WAIT_TIMEOUT"),
            () -> assertThat(afterRun).allSatisfy(stored -> assertThat(stored).containsEntry("status", "PENDING")
                .containsEntry("attempt_count", 1L).containsEntry("cycle_attempt_count", 1).containsEntry("published_at", null)),
            () -> assertThat(afterRun).allSatisfy(stored -> assertThat((String) stored.get("last_error")).doesNotContain("secret")),
            () -> assertThat(afterLate).as("timeout 뒤 늦은 성공·실패는 DB 를 바꾸지 않음").isEqualTo(afterRun)
        );
    }

    // ---------------------------------------------------------------- 예산·차단·재개

    @DisplayName("연속 실패는 5→10→20→40→60초 상한으로 늘고 10번째에 BLOCKED 이며, 재개는 누적 10·회차 0·최근 오류를 보존하고 다음 실행이 같은 원문을 보낸다.")
    @Test
    void exhaustsBudgetThenResumes() {
        record(301L);
        Map<String, Object> first = row(301L);
        List<Long> delays = failRepeatedly(301L, 10);
        Map<String, Object> blocked = row(301L);
        int callsWhenBlocked = SENDER.calls.size();

        CLOCK.set(BASE.plus(Duration.ofDays(1)), ZoneOffset.UTC);
        Result<List<RowResult>> dueWhileBlocked = relay.runDueBatch();
        Result<RowResult> explicitWhileBlocked = operations.runEvent("order-confirmed:301");
        Result<Transition> resumed = operations.resumeBlocked("order-confirmed:301");
        Map<String, Object> afterResume = row(301L);
        int callsAfterResume = SENDER.calls.size();
        Result<Transition> resumedAgain = operations.resumeBlocked("order-confirmed:301");
        Map<String, Object> afterSecondResume = row(301L);

        CLOCK.set(BASE.plus(Duration.ofDays(1)).plusSeconds(1), ZoneOffset.UTC);
        Result<RowResult> published = operations.runEvent("order-confirmed:301");
        Map<String, Object> afterPublish = row(301L);
        Result<Transition> resumePublished = operations.resumeBlocked("order-confirmed:301");

        assertAll(
            () -> assertThat(delays).as("실패 1~9 의 다음 시각 - 실패 관측 시각").containsExactly(5L, 10L, 20L, 40L, 60L, 60L, 60L, 60L, 60L),
            () -> assertThat(blocked).containsEntry("status", "BLOCKED").containsEntry("attempt_count", 10L)
                .containsEntry("cycle_attempt_count", 10).containsEntry("published_at", null)
                .containsEntry("last_error", "ATTEMPT_LIMIT_EXHAUSTED:SEND_COMPLETION_FAILED:java.lang.IllegalStateException"),
            () -> assertThat(dueWhileBlocked.value()).contains(List.of()),
            () -> assertThat(explicitWhileBlocked.value()).contains(new RowResult(id(first), RowOutcome.SKIPPED_BLOCKED)),
            () -> assertThat(resumed.value()).contains(Transition.APPLIED),
            () -> assertThat(afterResume).containsEntry("status", "PENDING").containsEntry("attempt_count", 10L)
                .containsEntry("cycle_attempt_count", 0).containsEntry("next_attempt_at", wall(BASE.plus(Duration.ofDays(1))))
                .containsEntry("last_error", blocked.get("last_error")),
            () -> assertThat(callsAfterResume).as("차단·재개 동안 send 없음").isEqualTo(callsWhenBlocked).isEqualTo(10),
            () -> assertThat(resumedAgain.value()).contains(Transition.ALREADY_PENDING),
            () -> assertThat(afterSecondResume).as("ALREADY_PENDING 은 감사 시각까지 그대로").isEqualTo(afterResume),
            () -> assertThat(published.value()).contains(new RowResult(id(first), RowOutcome.PUBLISHED)),
            () -> assertThat(afterPublish).containsEntry("status", "PUBLISHED").containsEntry("attempt_count", 11L)
                .containsEntry("cycle_attempt_count", 1).containsEntry("last_error", null),
            () -> assertThat(SENDER.calls).extracting(attempt -> attempt.message().payload()).as("모든 시도가 같은 저장 원문")
                .containsOnly((String) first.get("payload")),
            () -> assertThat(resumePublished.value()).contains(Transition.ALREADY_PUBLISHED),
            () -> assertThat(row(301L)).isEqualTo(afterPublish),
            () -> assertFacts(first, afterPublish)
        );
    }

    @DisplayName("broker 성공 뒤 probe 중단은 그대로 전파되고 PUBLISHED 를 저장하지 않으며, 회차 예산이 남지 않은 PENDING 은 다음 실행에서 send 없이 BLOCKED 다.")
    @Test
    void interruptedLastAttemptBlocksWithoutSend() {
        record(401L);
        failRepeatedly(401L, 9);
        CLOCK.set(nextAttempt(401L), ZoneOffset.UTC);
        IllegalStateException stop = new IllegalStateException("통제 중단");
        PROBE.failure = stop;

        assertThatThrownBy(() -> relay.runDueBatch()).isSameAs(stop);
        PROBE.failure = null;
        Map<String, Object> interrupted = row(401L);
        CLOCK.set(nextAttempt(401L), ZoneOffset.UTC);
        Result<List<RowResult>> restarted = relay.runDueBatch();

        assertAll(
            () -> assertThat(interrupted).containsEntry("status", "PENDING").containsEntry("attempt_count", 10L)
                .containsEntry("cycle_attempt_count", 10).containsEntry("published_at", null),
            () -> assertThat(restarted.outcome()).as("probe 예외 뒤 gate 해제").isEqualTo(Outcome.EXECUTED),
            () -> assertThat(restarted.value().orElseThrow()).extracting(RowResult::outcome).containsExactly(RowOutcome.BLOCKED),
            () -> assertThat(row(401L)).containsEntry("status", "BLOCKED").containsEntry("attempt_count", 10L)
                .containsEntry("cycle_attempt_count", 10).containsEntry("last_error", "UNCONFIRMED_ATTEMPT_LIMIT"),
            () -> assertThat(SENDER.calls).as("중단 뒤 새 send 없음").hasSize(10)
        );
    }

    @DisplayName("손상된 저장 본문은 send 없이 승인 code 로 즉시 BLOCKED 이고, 시도 예산을 1 소비한다.")
    @Test
    void invalidPayloadBlocksWithoutSend() {
        record(501L);
        jdbc.update("UPDATE order_confirmed_outbox SET payload = ? WHERE event_id = ?",
            "{\"eventType\":\"OrderConfirmed\",\"schemaVersion\":1}", "order-confirmed:501");

        Result<List<RowResult>> result = relay.runDueBatch();

        assertAll(
            () -> assertThat(result.value().orElseThrow()).extracting(RowResult::outcome).containsExactly(RowOutcome.BLOCKED),
            () -> assertThat(row(501L)).containsEntry("status", "BLOCKED").containsEntry("attempt_count", 1L)
                .containsEntry("cycle_attempt_count", 1).containsEntry("last_error", "INVALID_OUTBOX_MESSAGE"),
            () -> assertThat(SENDER.calls).isEmpty()
        );
    }

    // ---------------------------------------------------------------- DB 오류·명시 실행·입력

    @DisplayName("broker 성공 뒤 상태 저장이 실제 SQL 에서 실패하면 MARK_PUBLISHED 로 회차를 멈추고, 다음 행은 보내지 않으며 시작 commit 만 남는다.")
    @Test
    void stateFailureAfterBrokerSuccessStopsRound() throws Exception {
        record(601L);
        record(602L);
        long failing = id(row(601L));
        String schema = jdbc.queryForObject("SELECT DATABASE()", String.class);
        try (var root = rootDataSource(schema)) {
            JdbcTemplate admin = new JdbcTemplate(root);
            admin.execute("CREATE TRIGGER relay_test_fail_publish BEFORE UPDATE ON order_confirmed_outbox FOR EACH ROW BEGIN"
                + " IF NEW.status = 'PUBLISHED' AND NEW.id = " + failing
                + " THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'relay test publish failure'; END IF; END");
            try {
                assertThatThrownBy(() -> relay.runDueBatch()).isInstanceOfSatisfying(OrderOutboxRelayException.class, failure -> {
                    assertThat(failure.stage()).isEqualTo(OrderOutboxRelayException.Stage.MARK_PUBLISHED);
                    assertThat(failure.getMessage()).doesNotContain("relay test publish failure");
                });
            } finally {
                admin.execute("DROP TRIGGER IF EXISTS relay_test_fail_publish");
            }
        }

        assertAll(
            () -> assertThat(row(601L)).as("시작 commit 은 남고 발행 저장은 rollback").containsEntry("status", "PENDING")
                .containsEntry("attempt_count", 1L).containsEntry("published_at", null),
            () -> assertThat(row(602L)).as("다음 행은 시도하지 않음").containsEntry("attempt_count", 0L),
            () -> assertThat(SENDER.calls).hasSize(1),
            () -> assertThat(relay.runEvent("order-confirmed:602").outcome()).as("gate 해제").isEqualTo(Outcome.EXECUTED)
        );
    }

    @DisplayName("명시 실행은 미래 PENDING 을 NOT_DUE 로 두고, 없는 eventId·비정규 입력·상위 TX 는 거절하며, 결과 TX 의 다른 시도 번호는 바꾸지 않는다.")
    @Test
    void explicitRunAndStaleResults() {
        record(701L);
        CLOCK.set(BASE.minusSeconds(1), ZoneOffset.UTC);
        Result<RowResult> notDue = operations.runEvent("order-confirmed:701");
        Map<String, Object> untouched = row(701L);
        CLOCK.set(BASE, ZoneOffset.UTC);
        relay.runEvent("order-confirmed:701");
        Map<String, Object> published = row(701L);
        long id = id(published);

        assertAll(
            () -> assertThat(notDue.value()).contains(new RowResult(id, RowOutcome.NOT_DUE)),
            () -> assertThat(untouched).containsEntry("attempt_count", 0L),
            () -> assertThat(published).containsEntry("status", "PUBLISHED"),
            () -> assertThat(transactions.markPublished(id, 2L, now()).transition()).isEqualTo(Transition.ALREADY_PUBLISHED),
            () -> assertThat(transactions.recordFailure(id, 1L, "SEND_CALL_FAILED", now()).transition())
                .isEqualTo(Transition.ALREADY_PUBLISHED),
            () -> assertThat(row(701L)).as("변경 없는 결과는 감사 시각도 그대로").isEqualTo(published),
            () -> assertThatThrownBy(() -> relay.runEvent("order-confirmed:999")).isInstanceOf(IllegalArgumentException.class),
            () -> assertThat(List.of("order-confirmed:0", "order-confirmed:0701", "order-confirmed:9223372036854775808", "701", " "))
                .allSatisfy(input -> assertThatThrownBy(() -> operations.runEvent(input)).isInstanceOf(IllegalArgumentException.class)),
            () -> assertThatThrownBy(() -> operations.resumeBlocked("order-confirmed:999")).isInstanceOf(IllegalArgumentException.class),
            () -> assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> relay.runDueBatch()))
                .isInstanceOf(IllegalStateException.class),
            () -> assertThat(SENDER.calls).hasSize(1)
        );
    }

    @DisplayName("시도 중인 PENDING 에 다른 시도 번호의 결과가 오면 STALE_ATTEMPT 로 행을 바꾸지 않는다.")
    @Test
    void staleAttemptOnPendingChangesNothing() throws Exception {
        record(702L);
        CompletableFuture<OrderOutboxReceipt> pending = new CompletableFuture<>();
        SENDER.plan(attempt -> pending);
        Future<Result<List<RowResult>>> running = worker.submit(relay::runDueBatch);
        SENDER.awaitCalls(1);
        Map<String, Object> during = row(702L);
        long id = id(during);

        Transition stalePublish = transactions.markPublished(id, 2L, now()).transition();
        Transition staleFailure = transactions.recordFailure(id, 0L, "SEND_CALL_FAILED", now()).transition();
        Map<String, Object> afterStale = row(702L);
        pending.complete(new OrderOutboxReceipt("order-events-test", 0, 1L));

        assertAll(
            () -> assertThat(stalePublish).isEqualTo(Transition.STALE_ATTEMPT),
            () -> assertThat(staleFailure).isEqualTo(Transition.STALE_ATTEMPT),
            () -> assertThat(afterStale).isEqualTo(during),
            () -> assertThat(running.get(10, TimeUnit.SECONDS).value().orElseThrow()).extracting(RowResult::outcome)
                .containsExactly(RowOutcome.PUBLISHED)
        );
    }

    // ---------------------------------------------------------------- gate·종료

    @DisplayName("회차가 send 를 기다리는 동안 다른 회차·명시 실행·재개는 작업 없이 BUSY 이고, 회차는 끝까지 직렬로 이어진다.")
    @Test
    void concurrentEntriesAreBusy() throws Exception {
        record(801L);
        record(802L);
        CompletableFuture<OrderOutboxReceipt> pending = new CompletableFuture<>();
        SENDER.plan(attempt -> pending);
        Future<Result<List<RowResult>>> running = worker.submit(relay::runDueBatch);
        SENDER.awaitCalls(1);

        Result<List<RowResult>> batch = relay.runDueBatch();
        Result<RowResult> explicit = operations.runEvent("order-confirmed:802");
        Result<Transition> resume = operations.resumeBlocked("order-confirmed:802");
        Map<String, Object> secondDuring = row(802L);
        pending.complete(new OrderOutboxReceipt("order-events-test", 0, 1L));
        Result<List<RowResult>> finished = running.get(10, TimeUnit.SECONDS);

        assertAll(
            () -> assertThat(List.of(batch.outcome(), explicit.outcome(), resume.outcome())).containsOnly(Outcome.BUSY),
            () -> assertThat(List.of(batch.value(), explicit.value(), resume.value())).containsOnly(Optional.empty()),
            () -> assertThat(secondDuring).containsEntry("attempt_count", 0L),
            () -> assertThat(finished.value().orElseThrow()).extracting(RowResult::outcome)
                .containsExactly(RowOutcome.PUBLISHED, RowOutcome.PUBLISHED),
            () -> assertThat(SENDER.calls).extracting(attempt -> attempt.message().messageKey()).containsExactly("801", "802")
        );
    }

    @DisplayName("종료 요청은 send 를 기다리던 회차를 interrupt 로 끝내 결과를 저장하지 않고, 다음 행·이후 진입을 막으며 늦은 성공도 무시된다.")
    @Test
    void stopInterruptsWaitingRound() throws Exception {
        record(901L);
        record(902L);
        OrderOutboxExecutionGate ownGate = new OrderOutboxExecutionGate();
        OrderOutboxRelay ownRelay = new OrderOutboxRelay(transactions, verifier, SENDER, PROBE, ownGate, CLOCK,
            new OrderOutboxRelayProperties(false, Duration.ofSeconds(1), 10, Duration.ofSeconds(30), Duration.ofSeconds(5),
                Duration.ofSeconds(60), 10));
        CompletableFuture<OrderOutboxReceipt> pending = new CompletableFuture<>();
        SENDER.plan(attempt -> pending);
        Future<Object[]> running = worker.submit(() -> new Object[] {ownRelay.runDueBatch(), Thread.currentThread().isInterrupted()});
        SENDER.awaitCalls(1);

        ownGate.stop();
        boolean idle = ownGate.awaitIdle(Duration.ofSeconds(5));
        Object[] finished = running.get(10, TimeUnit.SECONDS);
        pending.complete(new OrderOutboxReceipt("order-events-test", 0, 1L));
        @SuppressWarnings("unchecked")
        Result<List<RowResult>> result = (Result<List<RowResult>>) finished[0];

        assertAll(
            () -> assertThat(idle).isTrue(),
            () -> assertThat(result.value().orElseThrow()).extracting(RowResult::outcome).containsExactly(RowOutcome.INTERRUPTED),
            () -> assertThat(finished[1]).as("interrupt 플래그 복원").isEqualTo(true),
            () -> assertThat(row(901L)).as("결과를 합성하지 않음").containsEntry("status", "PENDING").containsEntry("attempt_count", 1L)
                .containsEntry("last_error", null).containsEntry("published_at", null),
            () -> assertThat(row(902L)).containsEntry("attempt_count", 0L),
            () -> assertThat(ownGate.tryExecute(() -> 1).outcome()).isEqualTo(Outcome.STOPPING),
            () -> assertThat(SENDER.calls).hasSize(1)
        );
    }

    @DisplayName("종료 요청 뒤 send 가 동기 예외로 끝나면 실패 결과 TX 를 부르지 않고 INTERRUPTED 로 끝나며, 시작 commit·플래그·다음 행 미시도를 지킨다.")
    @Test
    void stopBeforeSendFailureStoresNothing() throws Exception {
        record(951L);
        record(952L);
        OrderOutboxExecutionGate ownGate = new OrderOutboxExecutionGate();
        OrderOutboxRelayTransactionService observed = observedTransactions();
        OrderOutboxRelay ownRelay = ownRelay(observed, verifier, ownGate);
        List<Map<String, Object>> atBegin = new CopyOnWriteArrayList<>();
        SENDER.plan(attempt -> {
            atBegin.add(row(951L));
            ownGate.stop();
            throw new IllegalStateException("send failed after stop");
        });

        Object[] finished = worker.submit(() -> new Object[] {ownRelay.runDueBatch(), Thread.currentThread().isInterrupted()})
            .get(10, TimeUnit.SECONDS);

        assertStoppedWithoutResult(finished, ownGate, atBegin.get(0), 952L);
        verify(observed, never()).recordFailure(anyLong(), anyLong(), anyString(), any());
        verify(observed, never()).blockInvalid(anyLong(), anyLong(), anyString());
        verify(observed, never()).markPublished(anyLong(), anyLong(), any());
        assertThat(SENDER.calls).hasSize(1);
    }

    @DisplayName("종료 요청 뒤 verifier 가 알려진 본문 오류를 던지면 차단 결과 TX 를 부르지 않고 send 없이 INTERRUPTED 로 끝난다.")
    @Test
    void stopBeforeInvalidMessageStoresNothing() throws Exception {
        record(961L);
        record(962L);
        OrderOutboxExecutionGate ownGate = new OrderOutboxExecutionGate();
        OrderOutboxRelayTransactionService observed = observedTransactions();
        List<Map<String, Object>> atBegin = new CopyOnWriteArrayList<>();
        OrderOutboxMessageVerifier stoppingVerifier = new OrderOutboxMessageVerifier() {
            @Override
            public OrderConfirmed parseAndValidate(OrderOutboxStoredMessage stored) {
                atBegin.add(row(961L));
                ownGate.stop();
                throw new OrderOutboxMessageException(OrderOutboxMessageException.Code.INVALID_OUTBOX_MESSAGE);
            }

            @Override
            public void requireSameFact(OrderConfirmed expected, OrderOutboxStoredMessage stored) {
                throw new UnsupportedOperationException("relay 는 호출하지 않는다");
            }
        };
        OrderOutboxRelay ownRelay = ownRelay(observed, stoppingVerifier, ownGate);

        Object[] finished = worker.submit(() -> new Object[] {ownRelay.runDueBatch(), Thread.currentThread().isInterrupted()})
            .get(10, TimeUnit.SECONDS);

        assertStoppedWithoutResult(finished, ownGate, atBegin.get(0), 962L);
        verify(observed, never()).blockInvalid(anyLong(), anyLong(), anyString());
        verify(observed, never()).recordFailure(anyLong(), anyLong(), anyString(), any());
        assertThat(SENDER.calls).as("send 없음").isEmpty();
    }

    /** 실제 TX 프록시에 위임하면서 결과 TX 메서드의 호출 자체를 관찰한다. rollback 된 호출도 호출로 기록된다. */
    private OrderOutboxRelayTransactionService observedTransactions() {
        return mock(OrderOutboxRelayTransactionService.class, delegatesTo(transactions));
    }

    private OrderOutboxRelay ownRelay(OrderOutboxRelayTransactionService tx, OrderOutboxMessageVerifier messageVerifier,
                                      OrderOutboxExecutionGate ownGate) {
        return new OrderOutboxRelay(tx, messageVerifier, SENDER, PROBE, ownGate, CLOCK,
            new OrderOutboxRelayProperties(false, Duration.ofSeconds(1), 10, Duration.ofSeconds(30), Duration.ofSeconds(5),
                Duration.ofSeconds(60), 10));
    }

    /** 결과 INTERRUPTED·플래그 유지·gate 해제/STOPPING·첫 행은 시작 commit 그대로(감사 시각 포함)·다음 행 미시도. */
    private void assertStoppedWithoutResult(Object[] finished, OrderOutboxExecutionGate ownGate, Map<String, Object> atBegin,
                                            long nextOrderId) throws InterruptedException {
        @SuppressWarnings("unchecked")
        Result<List<RowResult>> result = (Result<List<RowResult>>) finished[0];
        assertAll(
            () -> assertThat(result.value().orElseThrow()).extracting(RowResult::outcome).containsExactly(RowOutcome.INTERRUPTED),
            () -> assertThat(finished[1]).as("interrupt 플래그 유지").isEqualTo(true),
            () -> assertThat(ownGate.awaitIdle(Duration.ofSeconds(5))).as("gate 해제").isTrue(),
            () -> assertThat(ownGate.tryExecute(() -> 1).outcome()).isEqualTo(Outcome.STOPPING),
            () -> assertThat(atBegin).as("시작 commit").containsEntry("status", "PENDING").containsEntry("attempt_count", 1L)
                .containsEntry("cycle_attempt_count", 1).containsEntry("next_attempt_at", wall(BASE.plusSeconds(5)))
                .containsEntry("last_error", null),
            () -> assertThat(row((Long) atBegin.get("order_id"))).as("결과를 새로 저장하지 않음").isEqualTo(atBegin),
            () -> assertThat(row(nextOrderId)).as("다음 행 미시도").containsEntry("attempt_count", 0L)
        );
    }

    // ---------------------------------------------------------------- 조립

    @DisplayName("relay 는 앱 YAML 의 기본값과 기존 outboxRecordClock 하나를 쓰고, Clock bean 은 사건·Outbox 두 개뿐이다.")
    @Test
    void assemblyUsesExistingClock() {
        assertAll(
            () -> assertThat(properties.batchSize()).isEqualTo(10),
            () -> assertThat(properties.maxCycleAttempts()).isEqualTo(10),
            () -> assertThat(properties.initialRetryDelay()).isEqualTo(Duration.ofSeconds(5)),
            () -> assertThat(properties.maxRetryDelay()).isEqualTo(Duration.ofSeconds(60)),
            () -> assertThat(properties.fixedDelay()).isEqualTo(Duration.ofSeconds(1)),
            () -> assertThat(properties.enabled()).isFalse(),
            () -> assertThat(properties.sendWaitTimeout()).as("이 테스트 설정만 줄임").isEqualTo(Duration.ofMillis(500)),
            () -> assertThat(ReflectionTestUtils.getField(relay, "clock")).isSameAs(CLOCK),
            () -> assertThat(ReflectionTestUtils.getField(relay, "gate")).isSameAs(gate)
                .isSameAs(ReflectionTestUtils.getField(operations, "gate")),
            () -> assertThat(context.getBeansOfType(Clock.class).keySet())
                .containsExactlyInAnyOrder("orderConfirmationClock", OrderOutboxClockConfig.OUTBOX_RECORD_CLOCK)
        );
    }

    // ---------------------------------------------------------------- 도구

    /** 같은 행을 due 시각마다 실패시켜 n 번 기록하고, 실패 1~(n-1) 의 다음 시각 - 관측 시각(초)을 돌려준다. */
    private List<Long> failRepeatedly(long orderId, int times) {
        List<Long> delays = new ArrayList<>();
        for (int i = 0; i < times; i++) {
            Instant due = nextAttempt(orderId);
            CLOCK.set(due, ZoneOffset.UTC);
            SENDER.plan(attempt -> CompletableFuture.failedFuture(new IllegalStateException("broker down")));
            assertThat(operations.runEvent("order-confirmed:" + orderId).outcome()).isEqualTo(Outcome.EXECUTED);
            if (i < times - 1) {
                delays.add(Duration.between(due, nextAttempt(orderId)).toSeconds());
            }
        }
        return delays;
    }

    private Instant nextAttempt(long orderId) {
        return ((LocalDateTime) row(orderId).get("next_attempt_at")).toInstant(ZoneOffset.UTC);
    }

    /** 제품 recorder 로 실제 commit 한다. 첫 시도 시각은 현재 CLOCK 이다. */
    private void record(long orderId) {
        OrderConfirmed event = new OrderConfirmed(orderId, ZonedDateTime.of(2026, 11, 3, 0, 0, 0, 0, ZoneOffset.UTC),
            List.of(new OrderConfirmed.Item(11L, 2L), new OrderConfirmed.Item(12L, 1L)));
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> recorder.record(event));
    }

    private Map<String, Object> row(long orderId) {
        return jdbc.queryForMap(SELECT_ROW, "order-confirmed:" + orderId);
    }

    private static long id(Map<String, Object> row) {
        return ((Number) row.get("id")).longValue();
    }

    private static ZonedDateTime now() {
        return OrderOutboxRelay.now(CLOCK);
    }

    private static LocalDateTime wall(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static void assertFacts(Map<String, Object> before, Map<String, Object> after) {
        for (String column : List.of("id", "event_id", "order_id", "event_type", "schema_version", "message_key", "payload",
            "created_at", "deleted_at")) {
            assertThat(after.get(column)).as("불변 열 " + column).isEqualTo(before.get(column));
        }
    }

    private static SingleConnectionDataSource rootDataSource(String schema) {
        String url = System.getProperty("datasource.mysql-jpa.main.jdbc-url");
        String server = url.substring(0, url.lastIndexOf('/'));
        return new SingleConnectionDataSource(server + "/" + schema, "root",
            System.getProperty("datasource.mysql-jpa.main.password"), true);
    }

    /** 계획한 순서대로 결과를 돌려주는 sender 대역. 계획이 없으면 즉시 성공한다. 실제 broker 가 아니다. */
    static final class ControlledSender implements OrderOutboxSender {

        final List<OrderOutboxAttempt> calls = new CopyOnWriteArrayList<>();
        private final Queue<Function<OrderOutboxAttempt, CompletionStage<OrderOutboxReceipt>>> plans = new ConcurrentLinkedQueue<>();
        private final List<CompletableFuture<OrderOutboxReceipt>> issued = new CopyOnWriteArrayList<>();
        private final Semaphore sent = new Semaphore(0);

        void plan(Function<OrderOutboxAttempt, CompletionStage<OrderOutboxReceipt>> plan) {
            plans.add(plan);
        }

        void reset() {
            calls.clear();
            plans.clear();
            issued.clear();
            sent.drainPermits();
        }

        void awaitCalls(int count) throws InterruptedException {
            assertThat(sent.tryAcquire(count, 10, TimeUnit.SECONDS)).as("send 호출 " + count + "회").isTrue();
        }

        /** 테스트가 끝내지 않은 Future 를 정리해 대기 thread 가 남지 않게 한다. */
        void releasePending() {
            issued.forEach(future -> future.cancel(true));
        }

        @Override
        public CompletionStage<OrderOutboxReceipt> send(OrderOutboxAttempt attempt) {
            calls.add(attempt);
            sent.release();
            var plan = plans.poll();
            CompletionStage<OrderOutboxReceipt> stage = plan == null
                ? CompletableFuture.completedFuture(new OrderOutboxReceipt("order-events-test", 0, calls.size()))
                : plan.apply(attempt);
            issued.add(stage.toCompletableFuture());
            return stage;
        }
    }

    /** 실패가 지정되면 broker 확인 뒤 그 예외를 던진다. */
    static final class ControlledProbe implements OrderOutboxRelayProbe {

        volatile RuntimeException failure;

        @Override
        public void afterBrokerConfirmed(OrderOutboxAttempt attempt, OrderOutboxReceipt receipt) {
            if (failure != null) {
                throw failure;
            }
        }
    }
}
