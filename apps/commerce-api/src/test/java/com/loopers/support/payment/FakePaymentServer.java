package com.loopers.support.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class FakePaymentServer implements AutoCloseable {

    private final ObjectMapper mapper = new ObjectMapper();
    private final int port;
    private volatile HttpServer server;
    private volatile boolean running = true;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, JsonNode> transactions = new ConcurrentHashMap<>();
    private final List<Request> requests = new ArrayList<>();
    private final AtomicInteger createCalls = new AtomicInteger();
    private final AtomicInteger lookupCalls = new AtomicInteger();
    private final AtomicBoolean dropNextCreateResponse = new AtomicBoolean();
    private final AtomicBoolean holdNextCreateResponse = new AtomicBoolean();
    private final CountDownLatch responseRelease = new CountDownLatch(1);
    private final Queue<Reply> nextReplies = new ConcurrentLinkedQueue<>();
    private final Queue<Duration> nextDelays = new ConcurrentLinkedQueue<>();
    private final AtomicReference<Duration> delayEveryResponse = new AtomicReference<>();
    private final AtomicReference<Runnable> beforeNextLookupReply = new AtomicReference<>();
    private final AtomicReference<String> settleCreatedAs = new AtomicReference<>();
    private int delayedLookupEvery;
    private Duration patternedLookupDelay;
    private int patternLookupCalls;
    private int delayedLookupCalls;
    /** PG handler 진입부터 응답 전송 종료까지. 앱의 풀 획득·연결 수립 시간은 포함하지 않는다. */
    private final Queue<Handled> handled = new ConcurrentLinkedQueue<>();

    public FakePaymentServer() throws IOException {
        this(0);
    }

    /** 부하 실험처럼 앱 설정에 주소를 미리 적어야 할 때 고정 포트로 연다. 0이면 빈 포트를 고른다. */
    public FakePaymentServer(int port) throws IOException {
        server = start(port);
        this.port = server.getAddress().getPort();
    }

    private HttpServer start(int port) throws IOException {
        HttpServer started = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        started.createContext("/api/v1/payments", this::handle);
        started.setExecutor(workers);
        started.start();
        return started;
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    /** PG 프로세스가 죽은 장면. 포트를 닫아 연결을 거부한다. 거래 기록은 남긴다. */
    public synchronized void down() {
        server.stop(0);
        running = false;
    }

    /** 같은 포트로 다시 연다. 죽기 전 거래를 그대로 조회할 수 있다. */
    public synchronized void up() throws IOException {
        if (running) {
            return;
        }
        server = start(port);
        running = true;
    }

    /** 아직 쓰지 않은 지정 응답·지연 예약을 비운다. 장애를 걷어 낸 뒤 남은 예약이 복구 장면에 섞이지 않게 한다. */
    public synchronized void clearQueuedFaults() {
        nextReplies.clear();
        nextDelays.clear();
        delayedLookupEvery = 0;
        patternedLookupDelay = null;
    }

    /** 수신 순서대로 N번째 조회마다 지연한다. 생성과 거래 상태는 바꾸지 않는다. */
    public synchronized void delayEveryNthLookup(int every, Duration delay) {
        if (every < 1 || delay == null || delay.isNegative()) {
            throw new IllegalArgumentException("조회 간격과 지연 시간이 올바르지 않습니다.");
        }
        delayedLookupEvery = every;
        patternedLookupDelay = delay;
        patternLookupCalls = 0;
        delayedLookupCalls = 0;
    }

    public synchronized int patternLookupCalls() { return patternLookupCalls; }
    public synchronized int delayedLookupCalls() { return delayedLookupCalls; }

    /** 모든 응답을 지정한 시간만큼 늦춘다. null이면 해제한다. 생성 요청은 늦어진 뒤에도 거래를 만든다. */
    public void delayEveryResponse(Duration delay) { delayEveryResponse.set(delay); }

    /** 다음 조회 요청에 응답하기 직전에 한 번 실행한다. PG가 답한 직후 우리 쪽이 망가지는 장면에 쓴다. */
    public void beforeNextLookupReply(Runnable action) { beforeNextLookupReply.set(action); }

    /**
     * 생성 응답은 PENDING으로 주되, 저장한 거래는 지정한 결과로 바꿔 둔다. null이면 해제한다.
     * 실제 PG처럼 결과는 조회·callback으로만 알 수 있게 해, 부하 실험에서 거래마다 상태를 바꾸지 않아도 되게 한다.
     */
    public void settleCreatedAs(String status) { settleCreatedAs.set(status); }

    public void dropNextCreateResponse() { dropNextCreateResponse.set(true); }
    public void holdNextCreateResponse() { holdNextCreateResponse.set(true); }
    /** 다음 요청 하나에 줄 응답을 대기열에 넣는다. 여러 번 부르면 요청 순서대로 하나씩 쓴다. */
    public void replyNext(int status, String body) { nextReplies.add(new Reply(status, body)); }
    /** 다음 요청 하나의 응답을 지정한 시간만큼 늦춘다. 여러 번 부르면 요청 순서대로 하나씩 쓴다. */
    public void delayNextResponse(Duration delay) { nextDelays.add(delay); }
    public int createCalls() { return createCalls.get(); }
    public int lookupCalls() { return lookupCalls.get(); }
    public int transactionCount() { return transactions.size(); }

    public List<JsonNode> transactions() {
        return transactions.values().stream().<JsonNode>map(JsonNode::deepCopy).toList();
    }

    public synchronized List<Request> requests() { return List.copyOf(requests); }

    public List<Handled> handled() { return List.copyOf(handled); }

    /** 같은 서버를 여러 테스트가 쓸 때 거래·호출 수·다음 응답 설정을 비운다. 응답 보류(latch)는 되돌리지 않는다. */
    public synchronized void reset() {
        transactions.clear();
        requests.clear();
        createCalls.set(0);
        lookupCalls.set(0);
        dropNextCreateResponse.set(false);
        holdNextCreateResponse.set(false);
        nextReplies.clear();
        nextDelays.clear();
        delayEveryResponse.set(null);
        beforeNextLookupReply.set(null);
        settleCreatedAs.set(null);
        delayedLookupEvery = 0;
        patternedLookupDelay = null;
        patternLookupCalls = 0;
        delayedLookupCalls = 0;
        handled.clear();
    }

    public void changeStatus(String transactionKey, String status) {
        transactions.compute(transactionKey, (key, transaction) -> {
            if (transaction == null) {
                throw new IllegalArgumentException("거래를 찾을 수 없습니다.");
            }
            return ((ObjectNode) transaction.deepCopy()).put("status", status);
        });
    }

    /** 부하 종료 뒤 이미 만들어진 거래를 확정해 앱의 조회 복구를 확인한다. */
    public void completeAll(String status) {
        if (!List.of("SUCCESS", "FAILED").contains(status)) {
            throw new IllegalArgumentException("확정 상태가 필요합니다.");
        }
        transactions.replaceAll((key, transaction) -> ((ObjectNode) transaction.deepCopy()).put("status", status));
    }

    private void handle(HttpExchange exchange) throws IOException {
        long started = System.nanoTime();
        try {
            handlePayment(exchange);
        } finally {
            handled.add(new Handled(exchange.getRequestMethod(), Duration.ofNanos(System.nanoTime() - started)));
        }
    }

    private void handlePayment(HttpExchange exchange) throws IOException {
        try (exchange) {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            boolean create = "POST".equals(exchange.getRequestMethod());
            int createNumber;
            Duration delay;
            Reply override;
            synchronized (this) {
                requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().getRawPath(),
                    exchange.getRequestURI().getRawQuery(), exchange.getRequestHeaders().getFirst("X-USER-ID"),
                    exchange.getRequestHeaders().getFirst("Content-Type"), body));
                createNumber = create ? createCalls.incrementAndGet() : 0;
                if (!create) {
                    lookupCalls.incrementAndGet();
                }
                // 수신 기록 순서대로 먼저 배정한다. 지연 중 들어온 retry가 앞 응답을 가져가지 못하게 한다.
                Duration queued = nextDelays.poll();
                delay = queued != null ? queued : delayEveryResponse.get();
                override = nextReplies.poll();
                if (!create && delayedLookupEvery > 0) {
                    patternLookupCalls++;
                    if (queued == null && patternLookupCalls % delayedLookupEvery == 0) {
                        delay = patternedLookupDelay;
                        delayedLookupCalls++;
                    }
                }
            }
            if (delay != null) {
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (override != null) {
                reply(exchange, override.status(), override.body());
                return;
            }
            Runnable beforeLookup = create ? null : beforeNextLookupReply.getAndSet(null);
            if (beforeLookup != null) {
                beforeLookup.run();
            }
            if (create) {
                JsonNode request = mapper.readTree(body);
                String key = "TEST-TX-" + createNumber;
                JsonNode transaction = mapper.createObjectNode()
                    .put("transactionKey", key)
                    .put("orderId", request.path("orderId").asText())
                    .put("amount", request.path("amount").asLong())
                    .put("status", "PENDING");
                String settled = settleCreatedAs.get();
                transactions.put(key, settled == null ? transaction : ((ObjectNode) transaction.deepCopy()).put("status", settled));
                if (dropNextCreateResponse.getAndSet(false)) {
                    return;
                }
                if (holdNextCreateResponse.getAndSet(false)) {
                    try {
                        if (!responseRelease.await(Duration.ofSeconds(5).toMillis(), TimeUnit.MILLISECONDS)) {
                            return;
                        }
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                reply(exchange, 202, mapper.writeValueAsString(transaction));
            } else if (exchange.getRequestURI().getRawQuery() != null) {
                String orderId = URLDecoder.decode(exchange.getRequestURI().getRawQuery().substring("orderId=".length()),
                    StandardCharsets.UTF_8);
                List<JsonNode> matching = transactions.values().stream()
                    .filter(transaction -> orderId.equals(transaction.path("orderId").asText())).toList();
                reply(exchange, 200, mapper.writeValueAsString(matching));
            } else {
                String key = URLDecoder.decode(exchange.getRequestURI().getRawPath().substring("/api/v1/payments/".length()),
                    StandardCharsets.UTF_8);
                JsonNode transaction = transactions.get(key);
                reply(exchange, transaction == null ? 404 : 200, transaction == null ? "" : mapper.writeValueAsString(transaction));
            }
        }
    }

    private void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
    }

    @Override
    public void close() throws InterruptedException {
        responseRelease.countDown();
        if (running) {
            server.stop(0);
        }
        workers.shutdownNow();
        if (!workers.awaitTermination(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("테스트 PG worker가 종료되지 않았습니다.");
        }
    }

    public record Request(String method, String path, String rawQuery, String userId, String contentType, String body) {}

    /** method는 POST(생성) 또는 GET(조회), duration은 요청을 받은 뒤 응답을 다 보낼 때까지의 시간이다. */
    public record Handled(String method, Duration duration) {}
    private record Reply(int status, String body) {}
}
