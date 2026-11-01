package com.loopers.pgsimulator;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 로컬 PG(설계 2.10). 수동 실행은 고정 포트·금액 규칙·자동 처리로, 테스트는 임의 포트·등록 결과·자동 처리 없음으로 시작한다.
 * 테스트가 쓰는 확장점은 결과 정책과 {@link #transactions()}·{@link #callbacks()} 가 전부다.
 */
public final class PgSimulator implements AutoCloseable {

    static final int HTTP_WORKERS = 16;

    private static final byte[] LOOPBACK = {127, 0, 0, 1};
    private static final Duration TERMINATION_TIMEOUT = Duration.ofSeconds(5);
    private static final AtomicInteger SERVER_SEQUENCE = new AtomicInteger();

    private final HttpServer server;
    private final ExecutorService httpWorkers;
    private final TransactionStore store;
    private final CallbackSender callbacks;
    private final AutoSettlement autoSettlement;
    private final String baseUrl;
    private final String serverName;
    private final AtomicBoolean closed = new AtomicBoolean();

    private PgSimulator(String serverName, HttpServer server, ExecutorService httpWorkers, TransactionStore store,
                        CallbackSender callbacks, AutoSettlement autoSettlement) {
        this.serverName = serverName;
        this.server = server;
        this.httpWorkers = httpWorkers;
        this.store = store;
        this.callbacks = callbacks;
        this.autoSettlement = autoSettlement;
        this.baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public static PgSimulator start(PgSimulatorOptions options) {
        String serverName = "pg-simulator-" + SERVER_SEQUENCE.incrementAndGet();
        HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getByAddress(LOOPBACK), options.port()), 0);
        } catch (IOException e) {
            throw new UncheckedIOException("PG 서버 시작 실패: port=" + options.port(), e);
        }
        ExecutorService httpWorkers = Executors.newFixedThreadPool(HTTP_WORKERS, daemonThreads(serverName + "-http-"));
        ObjectMapper objectMapper = strictObjectMapper();
        TransactionStore store = new TransactionStore();
        CallbackSender callbacks = new CallbackSender(serverName + "-callback-", objectMapper);
        AutoSettlement autoSettlement = new AutoSettlement(
            options.settlement(), serverName + "-settlement", store, callbacks);
        server.createContext(PaymentHttpHandler.BASE_PATH,
            new PaymentHttpHandler(store, options.outcomePolicy(), autoSettlement, objectMapper));
        server.setExecutor(httpWorkers);
        server.start();
        return new PgSimulator(serverName, server, httpWorkers, store, callbacks, autoSettlement);
    }

    public String baseUrl() {
        return baseUrl;
    }

    /** 저장 거래의 조회·상태 변경·초기화 확장점. 상태 변경은 콜백을 보내지 않는다. */
    public TransactionStore transactions() {
        return store;
    }

    /** 저장 callbackUrl 로 알림을 1회씩 보내는 확장점. 재전송하지 않는다. */
    public CallbackSender callbacks() {
        return callbacks;
    }

    String httpThreadNamePrefix() {
        return serverName + "-http-";
    }

    String callbackThreadNamePrefix() {
        return serverName + "-callback-";
    }

    /** close 이후에도 호출할 수 있는 종료 진단. */
    public boolean isTerminated() {
        return httpWorkers.isTerminated() && callbacks.isTerminated();
    }

    /**
     * 서버 정지 → 자동 처리 예약 취소 → 대기 콜백은 미전송 결과로 완료·진행 콜백은 중단 → worker 유한 종료 확인 순서다.
     * 여러 번 호출해도 안전하며, 종료를 확인하지 못하면 IllegalStateException 으로 알린다.
     */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            server.stop(0);
            autoSettlement.shutdownNow();
            httpWorkers.shutdown();
            callbacks.shutdownNow();
        }
        awaitTermination();
    }

    private void awaitTermination() {
        try {
            boolean httpTerminated = httpWorkers.awaitTermination(TERMINATION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                || forceAndAwait(httpWorkers);
            boolean callbackTerminated = callbacks.awaitTermination(TERMINATION_TIMEOUT);
            boolean settlementTerminated = autoSettlement.awaitTermination(TERMINATION_TIMEOUT);
            if (httpTerminated && callbackTerminated && settlementTerminated) {
                return;
            }
            throw new IllegalStateException("PG worker 종료 미확인: http=" + httpTerminated
                + ", callback=" + callbackTerminated + ", settlement=" + settlementTerminated);
        } catch (InterruptedException e) {
            httpWorkers.shutdownNow();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("PG worker 종료 대기 중단: 종료 미확인", e);
        }
    }

    private static boolean forceAndAwait(ExecutorService workers) throws InterruptedException {
        workers.shutdownNow();
        return workers.awaitTermination(TERMINATION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
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
