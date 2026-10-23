package com.loopers.fixture.pg;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 테스트 준비·관측은 이 handle 의 메서드로만 한다(5.7). 별도 제어 HTTP 는 없고, 제어 호출은 PG 수신 횟수에 들어가지 않는다.
 * 제어 오류는 IllegalArgumentException, close 이후 호출은 입력과 관계없이 IllegalStateException 이다.
 * 그래서 입력 검사도 종료 판단 뒤 lifecycle 잠금 안에서 한다. 검사는 등록 전에 끝나므로 거절된 호출은 효과가 없다.
 * 제어·관측·reset 과 새 요청 접수는 {@link LifecycleGuard} 로 직렬화하고, worker 종료 대기는 그 잠금 밖에서 한다.
 * 생성·setStatus 는 콜백을 자동으로 보내지 않는다. 콜백은 {@link #sendCallbacks} 로만 보낸다.
 * HTTP·콜백 worker 종료를 확인하지 못하면 close 를 다시 호출해도 매번 실패를 알린다.
 */
public final class PgFixtureServer implements AutoCloseable {

    static final int HTTP_WORKERS = 16;

    private static final byte[] LOOPBACK = {127, 0, 0, 1};
    private static final Duration TERMINATION_TIMEOUT = Duration.ofSeconds(5);
    private static final AtomicInteger SERVER_SEQUENCE = new AtomicInteger();
    private static final int MAX_CALLBACK_COUNT = 10;

    private final HttpServer server;
    private final ExecutorService httpWorkers;
    private final InMemoryTransactionStore store;
    private final ScenarioRegistry scenarios;
    private final GateRegistry gates;
    private final RequestJournal journal;
    private final CallbackJournal callbackJournal;
    private final CallbackSender callbacks;
    private final LifecycleGuard lifecycle;
    private final String workerThreadNamePrefix;
    private final String baseUrl;

    private PgFixtureServer(HttpServer server, ExecutorService httpWorkers, InMemoryTransactionStore store,
                            ScenarioRegistry scenarios, GateRegistry gates, RequestJournal journal,
                            CallbackJournal callbackJournal, CallbackSender callbacks, LifecycleGuard lifecycle,
                            String workerThreadNamePrefix) {
        this.server = server;
        this.httpWorkers = httpWorkers;
        this.store = store;
        this.scenarios = scenarios;
        this.gates = gates;
        this.journal = journal;
        this.callbackJournal = callbackJournal;
        this.callbacks = callbacks;
        this.lifecycle = lifecycle;
        this.workerThreadNamePrefix = workerThreadNamePrefix;
        this.baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public static PgFixtureServer start() {
        String serverName = "pg-fixture-" + SERVER_SEQUENCE.incrementAndGet();
        String workerThreadNamePrefix = serverName + "-http-";
        ExecutorService httpWorkers = Executors.newFixedThreadPool(HTTP_WORKERS, daemonThreads(workerThreadNamePrefix));
        HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getByAddress(LOOPBACK), 0), 0);
        } catch (IOException e) {
            httpWorkers.shutdownNow();
            throw new UncheckedIOException("PG fixture 서버 시작 실패", e);
        }
        InMemoryTransactionStore store = new InMemoryTransactionStore();
        ScenarioRegistry scenarios = new ScenarioRegistry();
        GateRegistry gates = new GateRegistry();
        RequestJournal journal = new RequestJournal(Clock.systemUTC());
        LifecycleGuard lifecycle = new LifecycleGuard();
        ObjectMapper objectMapper = strictObjectMapper();
        CallbackJournal callbackJournal = new CallbackJournal(Clock.systemUTC());
        CallbackSender callbacks = new CallbackSender(serverName + "-callback-", callbackJournal, lifecycle, objectMapper);
        server.createContext(PaymentHttpHandler.BASE_PATH,
            new PaymentHttpHandler(store, scenarios, gates, journal, lifecycle, objectMapper));
        server.setExecutor(httpWorkers);
        server.start();
        return new PgFixtureServer(server, httpWorkers, store, scenarios, gates, journal, callbackJournal, callbacks,
            lifecycle, workerThreadNamePrefix);
    }

    public String baseUrl() {
        return baseUrl;
    }

    public FixtureState state() {
        return lifecycle.whileOpen(() -> {
            List<JournalEntry> entries = journal.snapshot();
            List<CallbackRecord> callbackRecords = callbackJournal.snapshot();
            return new FixtureState(
                store.findAll().stream().map(StoredTransaction::toSnapshot).toList(),
                RequestCounts.from(entries, callbackRecords),
                entries,
                lifecycle.activeRequests(),
                gates.snapshot(),
                callbackRecords,
                lifecycle.activeCallbackJobs());
        });
    }

    /** 거래 상태만 바꾼다. 콜백 전송·등록 plan·수신 기록에는 영향이 없다(5.7). */
    public void setStatus(String transactionKey, PaymentStatus status) {
        lifecycle.whileOpen(() -> {
            if (transactionKey == null || status == null) {
                throw new IllegalArgumentException("transactionKey·status 필수");
            }
            store.setStatus(transactionKey, status);
            return null;
        });
    }

    /** 등록만으로 거래·수신 기록은 바뀌지 않는다. GATE plan 의 gate 는 등록 시점에 함께 만든다. */
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
            return null;
        });
    }

    /**
     * 호출 시점의 거래 snapshot 과 저장 callbackUrl 로 count(1~10)번 알림을 보낸다. count 는 의도한 중복 횟수이며 retry 가 아니다.
     * 접수만 lifecycle 잠금 안에서 하고 전송·응답 대기는 콜백 전용 worker 가 잠금 없이 한다.
     * 반환 stage 는 모든 전송 시도가 끝나야 완료되며 커머스 업무 완료를 뜻하지 않는다.
     */
    public CompletionStage<CallbackBatchResult> sendCallbacks(String transactionKey, int count) {
        return lifecycle.whileOpen(() -> {
            if (transactionKey == null || count < 1 || count > MAX_CALLBACK_COUNT) {
                throw new IllegalArgumentException("transactionKey 필수, count 는 1~10");
            }
            StoredTransaction transaction = store.findByKey(transactionKey)
                .orElseThrow(() -> new IllegalArgumentException("없는 거래: " + transactionKey));
            lifecycle.acceptCallbackJobs(count);
            return callbacks.submit(transaction, count).minimalCompletionStage();
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
            store.clear();
            scenarios.clear();
            journal.clear();
            gates.clear();
            callbackJournal.clear();
        });
    }

    /** close 이후에도 호출할 수 있는 종료 진단. HTTP worker 와 콜백 worker 가 모두 끝났는지 본다. */
    public boolean isTerminated() {
        return httpWorkers.isTerminated() && callbacks.isTerminated();
    }

    String callbackThreadNamePrefix() {
        return callbacks.workerThreadNamePrefix();
    }

    String workerThreadNamePrefix() {
        return workerThreadNamePrefix;
    }

    /**
     * 신규 접수 차단 → delay·gate 대기 깨움(응답 없이 닫힘) → 서버 정지 → 대기 콜백은 미전송 결과로 완료·진행 콜백은
     * client 즉시 종료로 중단 → HTTP·콜백 worker 유한 종료 확인 순서다. 접수된 batch 는 모두 완료된다.
     */
    @Override
    public void close() {
        if (lifecycle.markClosed()) {
            gates.wakeAll();
            server.stop(0);
            httpWorkers.shutdown();
            callbacks.shutdownNow();
        }
        awaitWorkersTermination();
    }

    private void awaitWorkersTermination() {
        try {
            boolean httpTerminated = awaitOrForce(httpWorkers);
            boolean callbackTerminated = callbacks.awaitTermination(TERMINATION_TIMEOUT)
                || forceAndAwait(callbacks);
            if (httpTerminated && callbackTerminated) {
                return;
            }
            throw new IllegalStateException("PG fixture worker 종료 미확인: http=" + httpTerminated
                + ", callback=" + callbackTerminated);
        } catch (InterruptedException e) {
            httpWorkers.shutdownNow();
            callbacks.forceStop();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("PG fixture worker 종료 대기 중단: 종료 미확인", e);
        }
    }

    private static boolean awaitOrForce(ExecutorService workers) throws InterruptedException {
        if (workers.awaitTermination(TERMINATION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
            return true;
        }
        workers.shutdownNow();
        return workers.awaitTermination(TERMINATION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    }

    private static boolean forceAndAwait(CallbackSender callbacks) throws InterruptedException {
        callbacks.forceStop();
        return callbacks.awaitTermination(TERMINATION_TIMEOUT);
    }

    /** 중복 필드·JSON 뒤 추가 값처럼 해석이 갈리는 본문을 거절하기 위해 기본 ObjectMapper 보다 엄격하게 읽는다. */
    private static ObjectMapper strictObjectMapper() {
        return JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();
    }

    /** close 를 빠뜨린 테스트가 JVM 종료를 막지 않도록 daemon 으로 만든다. 종료 확인은 close 가 맡는다. */
    private static ThreadFactory daemonThreads(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
