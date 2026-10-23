package com.loopers.fixture.pg;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 콜백 자체 검증용 로컬 HTTP 수신 대역. 커머스 controller 가 아니며 받은 요청과 지정한 응답만 다룬다.
 * hold 중인 요청은 release 또는 최대 대기 뒤에 응답하므로 테스트가 실패해도 무기한 대기하지 않는다.
 */
final class CallbackReceiverStub implements AutoCloseable {

    static final String CALLBACK_PATH = "/api/v1/payments/callback";
    static final String REDIRECT_TARGET_PATH = "/redirect-target";

    private static final Duration HOLD_LIMIT = Duration.ofSeconds(5);
    private static final Duration TERMINATION_TIMEOUT = Duration.ofSeconds(5);

    private final HttpServer server;
    private final ExecutorService workers;
    private final List<ReceivedCallback> received = new ArrayList<>();
    private final Deque<Reply> replies = new ArrayDeque<>();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger redirectTargetHits = new AtomicInteger();
    private volatile CountDownLatch hold = new CountDownLatch(0);

    private CallbackReceiverStub(HttpServer server, ExecutorService workers) {
        this.server = server;
        this.workers = workers;
    }

    static CallbackReceiverStub start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), 0), 0);
        ExecutorService workers = Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "callback-receiver-stub");
            thread.setDaemon(true);
            return thread;
        });
        CallbackReceiverStub stub = new CallbackReceiverStub(server, workers);
        server.createContext(CALLBACK_PATH, stub::handleCallback);
        server.createContext(REDIRECT_TARGET_PATH, exchange -> {
            stub.redirectTargetHits.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.setExecutor(workers);
        server.start();
        return stub;
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    String callbackUrl() {
        return baseUrl() + CALLBACK_PATH;
    }

    /** 도착 순서대로 하나씩 쓸 응답. 비면 200 이다. */
    synchronized void replyWith(Reply... next) {
        replies.addAll(List.of(next));
    }

    void hold() {
        hold = new CountDownLatch(1);
    }

    void release() {
        hold.countDown();
    }

    int inFlight() {
        return inFlight.get();
    }

    int redirectTargetHits() {
        return redirectTargetHits.get();
    }

    synchronized List<ReceivedCallback> received() {
        return List.copyOf(received);
    }

    private void handleCallback(HttpExchange exchange) throws IOException {
        inFlight.incrementAndGet();
        try (exchange) {
            String body;
            try (InputStream in = exchange.getRequestBody()) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            Reply reply;
            synchronized (this) {
                received.add(new ReceivedCallback(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("Content-Type"), body));
                reply = replies.isEmpty() ? new Reply(200, Map.of()) : replies.pollFirst();
            }
            try {
                hold.await(HOLD_LIMIT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            reply.headers().forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
            exchange.sendResponseHeaders(reply.status(), -1);
        } finally {
            inFlight.decrementAndGet();
        }
    }

    @Override
    public void close() throws InterruptedException {
        release();
        server.stop(0);
        workers.shutdownNow();
        if (!workers.awaitTermination(TERMINATION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
            throw new IllegalStateException("콜백 수신 대역 worker 종료 미확인");
        }
    }

    record Reply(int status, Map<String, String> headers) {
    }

    record ReceivedCallback(String method, String path, String contentType, String body) {
    }
}
