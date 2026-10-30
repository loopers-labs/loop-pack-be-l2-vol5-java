package com.loopers.fixture.pg;

import com.loopers.pgsimulator.CallbackResult;
import com.loopers.pgsimulator.PaymentStatus;
import com.loopers.pgsimulator.PgSimulator;
import com.loopers.pgsimulator.PgSimulatorOptions;
import com.loopers.pgsimulator.Settlement;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 테스트용 PG handle(설계 5.7). pg-simulator 를 임의 포트·등록 결과·자동 처리 없음으로 띄우고 그 앞에 장애 주입 프록시를 둔다.
 * {@link #baseUrl()} 은 프록시 주소이며, 테스트는 PG 주소로 직접 요청하지 않는다. 제어 메서드의 의미는 5주차 fixture 와 같다.
 * 생성·setStatus 는 콜백을 자동으로 보내지 않는다. 콜백은 {@link #sendCallbacks} 로만 보낸다.
 */
public final class PgFixtureServer implements AutoCloseable {

    private static final Duration TERMINATION_TIMEOUT = Duration.ofSeconds(5);
    private static final AtomicInteger SERVER_SEQUENCE = new AtomicInteger();
    private static final int MAX_CALLBACK_COUNT = 10;

    private final PgSimulator pg;
    private final PgFaultProxy proxy;
    private final ScriptedOutcomePolicy outcomes;
    private final ScenarioRegistry scenarios;
    private final GateRegistry gates;
    private final RequestJournal journal;
    private final LifecycleGuard lifecycle;
    /** 결과가 나온 알림 중 실제로 보낸(sent=true) 수. 진행 중 전송은 아직 세지 않는다(5.7). */
    private final AtomicLong callbackSent = new AtomicLong();

    private PgFixtureServer(PgSimulator pg, PgFaultProxy proxy, ScriptedOutcomePolicy outcomes,
                            ScenarioRegistry scenarios, GateRegistry gates, RequestJournal journal,
                            LifecycleGuard lifecycle) {
        this.pg = pg;
        this.proxy = proxy;
        this.outcomes = outcomes;
        this.scenarios = scenarios;
        this.gates = gates;
        this.journal = journal;
        this.lifecycle = lifecycle;
    }

    public static PgFixtureServer start() {
        String name = "pg-fixture-" + SERVER_SEQUENCE.incrementAndGet();
        ScriptedOutcomePolicy outcomes = new ScriptedOutcomePolicy();
        ScenarioRegistry scenarios = new ScenarioRegistry();
        GateRegistry gates = new GateRegistry();
        RequestJournal journal = new RequestJournal(Clock.systemUTC());
        LifecycleGuard lifecycle = new LifecycleGuard();
        PgSimulator pg = PgSimulator.start(new PgSimulatorOptions(0, outcomes, Settlement.DISABLED));
        PgFaultProxy proxy;
        try {
            proxy = PgFaultProxy.start(name, pg.baseUrl(), scenarios, gates, journal, lifecycle);
        } catch (RuntimeException e) {
            pg.close();
            throw e;
        }
        return new PgFixtureServer(pg, proxy, outcomes, scenarios, gates, journal, lifecycle);
    }

    /** 장애 주입 프록시 주소. commerce.pg.base-url 에 주입한다. */
    public String baseUrl() {
        return proxy.baseUrl();
    }

    public FixtureState state() {
        return lifecycle.whileOpen(() -> {
            List<JournalEntry> entries = journal.snapshot();
            return new FixtureState(
                pg.transactions().findAll().stream()
                    .map(transaction -> new TransactionSnapshot(transaction.transactionKey(), transaction.orderId(),
                        transaction.amount(), transaction.status()))
                    .toList(),
                RequestCounts.from(entries, callbackSent.get()),
                entries,
                lifecycle.activeRequests(),
                gates.snapshot(),
                lifecycle.activeCallbackJobs());
        });
    }

    /** 거래 상태만 바꾼다. 콜백 전송·등록 plan·수신 기록에는 영향이 없다(5.7). */
    public void setStatus(String transactionKey, PaymentStatus status) {
        lifecycle.whileOpen(() -> {
            if (transactionKey == null || status == null) {
                throw new IllegalArgumentException("transactionKey·status 필수");
            }
            pg.transactions().setStatus(transactionKey, status);
            return null;
        });
    }

    /**
     * 등록만으로 거래·수신 기록은 바뀌지 않는다. GATE plan 의 gate 는 등록 시점에 함께 만든다.
     * CREATE plan 은 생성 결과(PG 정책)와 reply(프록시)로 나눠 같은 순서로 넣는다(5.7 plan 소비 정렬).
     */
    public void enqueuePlan(PgOperation operation, String target, ResponsePlan plan) {
        lifecycle.whileOpen(() -> {
            if (operation == null || target == null || target.isEmpty() || plan == null) {
                throw new IllegalArgumentException("operation·target·plan 필수");
            }
            plan.validateFor(operation);
            if (plan.effectiveReply() == ReplyMode.GATE) {
                gates.register(plan.gateId());
            }
            scenarios.enqueue(operation, target, plan);
            if (operation == PgOperation.CREATE) {
                outcomes.enqueue(target, plan.effectiveCreateOutcome());
            }
            return null;
        });
    }

    /**
     * 호출 시점의 거래 snapshot 과 저장 callbackUrl 로 count(1~10)번 알림을 보낸다. count 는 의도한 중복 횟수이며 retry 가 아니다.
     * 접수만 lifecycle 잠금 안에서 하고 전송·응답 대기는 PG 의 콜백 worker 가 한다.
     * 반환 stage 는 모든 전송 시도가 끝나야 완료되며 커머스 업무 완료를 뜻하지 않는다.
     */
    public CompletionStage<CallbackBatchResult> sendCallbacks(String transactionKey, int count) {
        return lifecycle.whileOpen(() -> {
            if (transactionKey == null || count < 1 || count > MAX_CALLBACK_COUNT) {
                throw new IllegalArgumentException("transactionKey 필수, count 는 1~10");
            }
            com.loopers.pgsimulator.TransactionSnapshot snapshot = pg.transactions().findByKey(transactionKey)
                .orElseThrow(() -> new IllegalArgumentException("없는 거래: " + transactionKey));
            lifecycle.acceptCallbackJobs(count);
            List<CompletableFuture<CallbackDelivery>> deliveries = new ArrayList<>();
            for (int attempt = 1; attempt <= count; attempt++) {
                int number = attempt;
                // 집계·접수 해제를 결과 완료보다 먼저 해서, batch 완료를 본 테스트가 곧바로 조용한 상태를 보게 한다.
                deliveries.add(pg.callbacks().send(snapshot).thenApply(result -> {
                    if (result.sent()) {
                        callbackSent.incrementAndGet();
                    }
                    lifecycle.exitCallbackJob();
                    return toDelivery(number, result);
                }));
            }
            return CompletableFuture.allOf(deliveries.toArray(CompletableFuture[]::new))
                .thenApply(done -> new CallbackBatchResult(transactionKey,
                    deliveries.stream().map(CompletableFuture::join).toList()))
                .minimalCompletionStage();
        });
    }

    /** 반복 해제는 성공한다. 해제 전에 이미 만료된 gate 는 그대로 둔다. */
    public void releaseGate(String gateId) {
        lifecycle.whileOpen(() -> {
            gates.release(gateId);
            return null;
        });
    }

    /** 처리 중인 요청이나 접수·진행 중 콜백 작업이 있으면 아무것도 지우지 않고 IllegalStateException 이다. */
    public void reset() {
        lifecycle.whenQuiet(() -> {
            pg.transactions().clear();
            outcomes.clear();
            scenarios.clear();
            journal.clear();
            gates.clear();
            callbackSent.set(0);
        });
    }

    /** close 이후에도 호출할 수 있는 종료 진단. 프록시 worker 와 PG worker 가 모두 끝났는지 본다. */
    public boolean isTerminated() {
        return proxy.isTerminated() && pg.isTerminated();
    }

    String workerThreadNamePrefix() {
        return proxy.workerThreadNamePrefix();
    }

    /**
     * 신규 접수 차단 → delay·gate 대기 깨움(응답 없이 닫힘) → 프록시 정지 → PG 종료(대기 콜백은 미전송 결과, 진행 콜백은 중단)
     * → worker 유한 종료 확인 순서다. 접수된 batch 는 모두 완료된다. 종료를 확인하지 못하면 IllegalStateException 이다.
     */
    @Override
    public void close() {
        if (lifecycle.markClosed()) {
            gates.wakeAll();
            proxy.stop();
        }
        IllegalStateException failure = null;
        try {
            if (!proxy.awaitTermination(TERMINATION_TIMEOUT)) {
                failure = new IllegalStateException("PG 프록시 worker 종료 미확인");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failure = new IllegalStateException("PG 프록시 worker 종료 대기 중단: 종료 미확인", e);
        }
        try {
            pg.close();
        } catch (IllegalStateException e) {
            if (failure == null) {
                failure = e;
            } else {
                failure.addSuppressed(e);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static CallbackDelivery toDelivery(int attempt, CallbackResult result) {
        return new CallbackDelivery(attempt, result.sent(), result.httpStatus(), result.failure());
    }
}
