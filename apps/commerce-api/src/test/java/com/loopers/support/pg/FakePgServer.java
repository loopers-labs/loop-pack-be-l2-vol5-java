package com.loopers.support.pg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 실습이 정의한 로컬 테스트 PG 계약을 따르는 작은 HTTP 서버. 별도 프로세스가 아니라 테스트 안에서 뜬다.
 *
 * <p>무작위 지연이나 성공 확률은 없다. 시나리오는 {@link #onCreate}, {@link #failLookups}, {@link #delayLookups} 로 한 줄에
 * 고정하고, 거래 수·호출 횟수·받은 요청을 관찰해 외부 효과를 검증한다. 계약을 어기는 요청(헤더·필드 누락, 문자열 금액 등)은
 * 4xx 로 거절하므로 adapter 가 형식을 틀리면 테스트가 실패한다.
 *
 * <p>{@code 127.0.0.1} 임의 포트에 뜨고 {@link #close()} 에서 서버와 worker 를 정리한다.
 */
public final class FakePgServer implements AutoCloseable {

    public static final String USER_ID = "135135";
    public static final String PAYMENTS_PATH = "/api/v1/payments";
    public static final String REJECTED_CODE = "TEST_PG_REJECTED_BEFORE_CREATE";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpServer server;
    private final ExecutorService executor;
    private final List<FakeTransaction> transactions = new CopyOnWriteArrayList<>();
    private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
    private final AtomicInteger sequence = new AtomicInteger();
    private final AtomicInteger createCalls = new AtomicInteger();
    private final AtomicInteger lookupByKeyCalls = new AtomicInteger();
    private final AtomicInteger lookupByOrderCalls = new AtomicInteger();

    private volatile CreateBehavior createBehavior = accept();
    private volatile Duration lookupDelay = Duration.ZERO;
    private volatile Reply lookupOverride;
    private final AtomicInteger lookupFailuresLeft = new AtomicInteger();
    private volatile int lookupFailureStatus;

    private FakePgServer(HttpServer server, ExecutorService executor) {
        this.server = server;
        this.executor = executor;
    }

    public static FakePgServer start() throws IOException {
        InetAddress loopback = InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
        HttpServer server = HttpServer.create(new InetSocketAddress(loopback, 0), 0);
        ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "fake-pg-worker");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);
        FakePgServer fake = new FakePgServer(server, executor);
        server.createContext("/", fake::handle);
        server.start();
        return fake;
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
        try {
            executor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---- 시나리오 ----

    /** 결제 생성 요청에 대한 응답을 정한다. 기본은 {@link #accept()} 다. */
    public void onCreate(CreateBehavior behavior) {
        this.createBehavior = behavior;
    }

    /** 거래 조회(키·주문 모두)가 항상 이 상태로 응답하게 한다. */
    public void failLookups(int status) {
        this.lookupOverride = new Reply(status, "{\"code\":\"FAKE_PG_LOOKUP_FAILURE\"}", false);
    }

    /** 앞으로 들어오는 거래 조회 중 처음 {@code count} 건만 이 상태로 실패시키고, 그다음부터는 정상으로 응답한다. */
    public void failNextLookups(int count, int status) {
        this.lookupFailureStatus = status;
        this.lookupFailuresLeft.set(count);
    }

    /** 거래 조회가 항상 이 상태와 본문으로 응답하게 한다. 해석할 수 없는 본문을 만들 때 쓴다. */
    public void respondToLookupsWith(int status, String body) {
        this.lookupOverride = new Reply(status, body, false);
    }

    /** 거래 조회가 응답 전에 이만큼 기다리게 한다. */
    public void delayLookups(Duration delay) {
        this.lookupDelay = delay;
    }

    public void resetLookupFaults() {
        this.lookupDelay = Duration.ZERO;
        this.lookupOverride = null;
        this.lookupFailuresLeft.set(0);
    }

    /** 거래·요청 기록·호출 횟수·시나리오를 처음 상태로 되돌린다. 서버는 그대로 두므로 같은 주소를 계속 쓸 수 있다. */
    public synchronized void reset() {
        transactions.clear();
        requests.clear();
        sequence.set(0);
        createCalls.set(0);
        lookupByKeyCalls.set(0);
        lookupByOrderCalls.set(0);
        createBehavior = accept();
        resetLookupFaults();
    }

    /** 거래를 직접 만든다. 같은 주문에 거래를 더하거나 금액이 다른 거래를 만드는 시나리오에 쓴다. */
    public synchronized FakeTransaction addTransaction(String orderId, long amount, String status) {
        FakeTransaction transaction = new FakeTransaction(
            String.format("TEST-TX-%04d", sequence.incrementAndGet()), orderId, amount, status
        );
        transactions.add(transaction);
        return transaction;
    }

    /** 외부 거래의 상태를 바꾼다(PENDING → SUCCESS 등). */
    public void setStatus(String transactionKey, String status) {
        transactions.stream()
            .filter(transaction -> transaction.key().equals(transactionKey))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("거래가 없습니다: " + transactionKey))
            .setStatus(status);
    }

    // ---- 관찰 ----

    public int createCalls() {
        return createCalls.get();
    }

    public int lookupByKeyCalls() {
        return lookupByKeyCalls.get();
    }

    public int lookupByOrderCalls() {
        return lookupByOrderCalls.get();
    }

    public int transactionCount() {
        return transactions.size();
    }

    public List<FakeTransaction> transactionsOf(String orderId) {
        return transactions.stream().filter(transaction -> transaction.orderId().equals(orderId)).toList();
    }

    public List<RecordedRequest> requests() {
        return List.copyOf(requests);
    }

    public RecordedRequest lastRequest() {
        return requests.get(requests.size() - 1);
    }

    // ---- 결제 생성 시나리오 ----

    /** 거래를 만들고 202 PENDING 으로 응답한다. */
    public static CreateBehavior accept() {
        return (server, body) -> {
            FakeTransaction transaction = server.addTransaction(
                body.get("orderId").asText(), body.get("amount").asLong(), "PENDING"
            );
            return Reply.json(202, transaction.toMap());
        };
    }

    /** 거래를 만든 뒤 응답을 보내지 않고 연결을 끊는다. 거래는 있는데 호출자는 결과를 모른다. */
    public static CreateBehavior acceptThenDropResponse() {
        return (server, body) -> {
            server.addTransaction(body.get("orderId").asText(), body.get("amount").asLong(), "PENDING");
            return Reply.drop();
        };
    }

    /** 거래를 만든 뒤 응답을 늦게 보낸다. 호출자의 읽기 timeout 이 더 짧으면 거래만 남는다. */
    public static CreateBehavior acceptAfterDelay(Duration delay) {
        return (server, body) -> {
            FakeTransaction transaction = server.addTransaction(
                body.get("orderId").asText(), body.get("amount").asLong(), "PENDING"
            );
            Thread.sleep(delay.toMillis());
            return Reply.json(202, transaction.toMap());
        };
    }

    /** 거래를 저장하기 전에 확정 거절한다. 거래는 만들지 않는다. */
    public static CreateBehavior rejectBeforeCreate() {
        return (server, body) -> {
            Map<String, Object> rejection = new LinkedHashMap<>();
            rejection.put("code", REJECTED_CODE);
            rejection.put("orderId", body.get("orderId").asText());
            rejection.put("amount", body.get("amount").asLong());
            rejection.put("transactionCreated", false);
            return Reply.json(422, rejection);
        };
    }

    /** 거래를 만들지 않고 정해 둔 상태와 본문으로 응답한다. 변조된 거절·수락 응답을 만들 때 쓴다. */
    public static CreateBehavior respond(int status, String body) {
        return (server, requestBody) -> new Reply(status, body, false);
    }

    // ---- 처리 ----

    private void handle(HttpExchange exchange) {
        Reply reply;
        try {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            RecordedRequest request = record(exchange, body);
            requests.add(request);
            reply = route(request);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            exchange.close();
            return;
        } catch (Exception e) {
            reply = error(500, "FAKE_PG_ERROR");
        }
        send(exchange, reply);
    }

    private Reply route(RecordedRequest request) throws Exception {
        String path = request.rawPath();
        boolean isCreate = request.method().equals("POST") && path.equals(PAYMENTS_PATH);
        if (isCreate) {
            createCalls.incrementAndGet();
        }
        if (!USER_ID.equals(request.headers().get("x-user-id"))) {
            return error(401, "UNAUTHORIZED");
        }
        if (isCreate) {
            return create(request);
        }
        if (request.method().equals("GET") && path.equals(PAYMENTS_PATH)) {
            lookupByOrderCalls.incrementAndGet();
            Reply fault = lookupFault();
            return fault != null ? fault : listByOrder(request);
        }
        if (request.method().equals("GET") && path.startsWith(PAYMENTS_PATH + "/")) {
            lookupByKeyCalls.incrementAndGet();
            Reply fault = lookupFault();
            return fault != null ? fault : getByKey(decode(path.substring(PAYMENTS_PATH.length() + 1)));
        }
        return error(404, "NOT_FOUND");
    }

    private Reply create(RecordedRequest request) throws Exception {
        JsonNode body = request.json();
        if (!isValidCreateBody(body)) {
            return error(400, "BAD_REQUEST");
        }
        return createBehavior.handle(this, body);
    }

    // 계약: orderId 는 6자 이상 문자열, amount 는 원화 정수, 나머지는 비어 있지 않은 문자열.
    private boolean isValidCreateBody(JsonNode body) {
        return body != null && body.isObject()
            && isText(body.get("orderId")) && body.get("orderId").asText().length() >= 6
            && body.get("amount") != null && body.get("amount").isIntegralNumber()
            && isText(body.get("cardType"))
            && isText(body.get("cardNo"))
            && isText(body.get("callbackUrl"));
    }

    private boolean isText(JsonNode node) {
        return node != null && node.isTextual() && !node.asText().isBlank();
    }

    private Reply lookupFault() throws InterruptedException {
        Duration delay = lookupDelay;
        if (!delay.isZero()) {
            Thread.sleep(delay.toMillis());
        }
        if (lookupFailuresLeft.getAndUpdate(left -> left > 0 ? left - 1 : 0) > 0) {
            return error(lookupFailureStatus, "FAKE_PG_LOOKUP_FAILURE");
        }
        return lookupOverride;
    }

    private Reply getByKey(String transactionKey) throws Exception {
        return transactions.stream()
            .filter(transaction -> transaction.key().equals(transactionKey))
            .findFirst()
            .map(transaction -> Reply.json(200, transaction.toMap()))
            .orElseGet(() -> error(404, "NOT_FOUND"));
    }

    private Reply listByOrder(RecordedRequest request) throws Exception {
        String orderId = request.queryParams().get("orderId");
        if (orderId == null) {
            return error(400, "BAD_REQUEST");
        }
        List<Map<String, Object>> found = new ArrayList<>();
        for (FakeTransaction transaction : transactionsOf(orderId)) {
            found.add(transaction.toMap());
        }
        return Reply.json(200, found);
    }

    private RecordedRequest record(HttpExchange exchange, String body) {
        Map<String, String> headers = new LinkedHashMap<>();
        exchange.getRequestHeaders().forEach((name, values) -> headers.put(name.toLowerCase(), values.get(0)));
        return new RecordedRequest(
            exchange.getRequestMethod(),
            exchange.getRequestURI().getRawPath(),
            exchange.getRequestURI().getRawQuery(),
            headers,
            body
        );
    }

    // 클라이언트가 이미 timeout 으로 끊었으면 쓰기가 실패한다. 서버 쪽에서는 정상적인 상황이다.
    private void send(HttpExchange exchange, Reply reply) {
        try {
            if (reply.dropConnection()) {
                exchange.close();
                return;
            }
            byte[] bytes = reply.body() == null ? new byte[0] : reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            if (bytes.length == 0) {
                exchange.sendResponseHeaders(reply.status(), -1);
            } else {
                exchange.sendResponseHeaders(reply.status(), bytes.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            }
        } catch (IOException ignored) {
            // 응답을 받을 상대가 없다.
        } finally {
            exchange.close();
        }
    }

    private static Reply error(int status, String code) {
        return new Reply(status, "{\"code\":\"" + code + "\"}", false);
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    // ---- 값 타입 ----

    @FunctionalInterface
    public interface CreateBehavior {
        Reply handle(FakePgServer server, JsonNode requestBody) throws Exception;
    }

    /** 서버가 보낼 응답. {@code dropConnection} 이면 헤더도 보내지 않고 연결을 끊는다. */
    public record Reply(int status, String body, boolean dropConnection) {
        public static Reply json(int status, Object value) {
            try {
                return new Reply(status, MAPPER.writeValueAsString(value), false);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        public static Reply drop() {
            return new Reply(-1, null, true);
        }
    }

    /** 서버가 받은 요청. 헤더 이름은 소문자다. */
    public record RecordedRequest(
        String method, String rawPath, String rawQuery, Map<String, String> headers, String body
    ) {
        /** 디코딩한 query 파라미터. 같은 이름이 여러 번 오면 마지막 값이다. */
        public Map<String, String> queryParams() {
            Map<String, String> params = new LinkedHashMap<>();
            if (rawQuery == null || rawQuery.isEmpty()) {
                return params;
            }
            for (String pair : rawQuery.split("&")) {
                int separator = pair.indexOf('=');
                String name = separator < 0 ? pair : pair.substring(0, separator);
                String value = separator < 0 ? "" : pair.substring(separator + 1);
                params.put(decode(name), decode(value));
            }
            return params;
        }

        public JsonNode json() {
            try {
                return MAPPER.readTree(body);
            } catch (IOException e) {
                return null;
            }
        }
    }

    /** PG 쪽 거래. 상태만 바뀐다. */
    public static final class FakeTransaction {
        private final String key;
        private final String orderId;
        private final long amount;
        private volatile String status;

        private FakeTransaction(String key, String orderId, long amount, String status) {
            this.key = key;
            this.orderId = orderId;
            this.amount = amount;
            this.status = status;
        }

        public String key() {
            return key;
        }

        public String orderId() {
            return orderId;
        }

        public long amount() {
            return amount;
        }

        public String status() {
            return status;
        }

        void setStatus(String status) {
            this.status = status;
        }

        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("transactionKey", key);
            map.put("orderId", orderId);
            map.put("amount", amount);
            map.put("status", status);
            return map;
        }
    }
}
