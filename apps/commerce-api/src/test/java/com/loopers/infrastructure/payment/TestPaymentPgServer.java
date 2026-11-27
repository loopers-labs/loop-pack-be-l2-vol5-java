package com.loopers.infrastructure.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 계약 테스트용 로컬 PG 서버 (설계 1-6 결정 ㉖). JDK 내장 HttpServer 를 포트 0 으로 띄워 진짜 소켓을 거친다.
 * 테스트가 경로별 응답을 미리 정하고, 서버는 받은 요청(메서드·경로·쿼리·헤더·본문)과 횟수를 기록한다.
 * 지연은 무작위가 아니라 래치로만 만든다. holdResponses() 뒤의 요청은 releaseResponses() 까지 응답을 보내지 않는다.
 * 카드 번호가 본문에 들어오므로 이 클래스는 아무것도 로그에 남기지 않는다.
 * <p>
 * 10주차: enableTransactionStore() 를 켜면 POST /api/v1/payments 가 정해진 응답 대신 실제 거래를 만들어 보관한다 (설계 3-3).
 * 장애 대상 주문은 거래를 SUCCESS 로 저장한 뒤 응답만 유실시킨다. 저장이 먼저이고 유실이 나중이라는 순서가 이 fixture 의 핵심이다.
 * 거래 보관 모드를 켜지 않으면 기존 "정해진 응답" 계약이 그대로 유지된다.
 */
public class TestPaymentPgServer implements AutoCloseable {

    private static final String PAYMENTS_PATH = "/api/v1/payments";
    private static final long HOLD_TIMEOUT_SECONDS = 30;
    private static final String TRANSACTION_KEY_FORMAT = "TEST-TX-%06d";
    private static final String PENDING = "PENDING";
    private static final String SUCCESS = "SUCCESS";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 서버가 받은 요청 한 건. query 는 디코딩된 값, rawQuery 는 클라이언트가 보낸 그대로다.
     */
    public record ReceivedRequest(
        String method, String path, String rawQuery, Map<String, String> query, Map<String, List<String>> headers, String body
    ) {
        public String header(String name) {
            List<String> values = headers.get(name.toLowerCase());
            return values == null || values.isEmpty() ? null : values.get(0);
        }
    }

    private record Response(int status, String body) {}

    /**
     * 보관 중인 외부 거래 1건. 같은 providerOrderId 에는 하나만 생긴다.
     */
    public record StoredTransaction(String transactionKey, String providerOrderId, long amount, String status) {}

    private final HttpServer server;
    private final List<ReceivedRequest> received = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Response> transactionResponses = new ConcurrentHashMap<>();
    private final Map<String, Response> listResponses = new ConcurrentHashMap<>();
    private volatile Response createResponse = new Response(202, null);
    private volatile CountDownLatch hold = new CountDownLatch(0);

    // 10주차 거래 보관 모드
    private volatile boolean transactionStore;
    private final Map<String, StoredTransaction> transactions = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> createCounts = new ConcurrentHashMap<>();
    private final Map<String, CountDownLatch> responseDrops = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    public TestPaymentPgServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        // 기본 실행기는 단일 스레드라 보류된 요청 하나가 뒤의 요청을 전부 막는다. 연속 timeout 장면을 위해 풀로 바꾼다.
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /**
     * 다음 create(POST) 응답. body 가 null 이면 본문 없이 보낸다.
     */
    public void respondToCreate(int status, String body) {
        createResponse = new Response(status, body);
    }

    /**
     * GET /api/v1/payments/{transactionKey} 응답. 정하지 않은 키는 404 다.
     */
    public void respondToTransaction(String transactionKey, int status, String body) {
        transactionResponses.put(transactionKey, new Response(status, body));
    }

    /**
     * GET /api/v1/payments?orderId={orderId} 응답. orderId 는 디코딩된 값으로 맞춘다. 정하지 않은 주문은 200 + 빈 목록이다.
     */
    public void respondToList(String orderId, int status, String body) {
        listResponses.put(orderId, new Response(status, body));
    }

    /**
     * 이후 모든 요청의 응답을 releaseResponses() 까지 보류한다. timeout 장면용.
     */
    public void holdResponses() {
        hold = new CountDownLatch(1);
    }

    public void releaseResponses() {
        hold.countDown();
    }

    /**
     * 거래 보관 모드. 켜면 POST /api/v1/payments 가 respondToCreate 대신 실제 거래를 만들어 보관하고,
     * 정해진 목록 응답이 없는 주문의 GET 조회는 보관된 거래로 답한다. 기존 "정해진 응답" API 는 그대로 쓸 수 있다.
     */
    public void enableTransactionStore() {
        transactionStore = true;
    }

    /**
     * 이 주문의 create 만 "거래를 SUCCESS 로 저장한 뒤 응답을 유실"시킨다. 다른 주문은 영향을 받지 않는다.
     * 거래 저장이 먼저이고 응답 유실이 나중이다. 해제는 clearResponseDrops() 다.
     */
    public void dropCreateResponseFor(String providerOrderId) {
        responseDrops.put(providerOrderId, new CountDownLatch(1));
    }

    /**
     * 응답 유실 주입을 모두 해제한다. **보관된 거래와 호출 횟수는 지우지 않는다.**
     * 복구 단계는 장애 때 생긴 같은 거래를 그대로 조회해야 하기 때문이다.
     */
    public void clearResponseDrops() {
        responseDrops.values().forEach(CountDownLatch::countDown);
        responseDrops.clear();
    }

    public boolean hasResponseDrops() {
        return !responseDrops.isEmpty();
    }

    /**
     * 외부 거래의 최종 성공 사실을 기록한다. callback 은 보내지 않는다. 거래가 없으면 아무것도 하지 않는다.
     */
    public void markSuccess(String providerOrderId) {
        transactions.computeIfPresent(providerOrderId, (key, transaction) -> withStatus(transaction, SUCCESS));
    }

    public StoredTransaction transactionOf(String providerOrderId) {
        return transactions.get(providerOrderId);
    }

    public List<StoredTransaction> storedTransactions() {
        return transactions.values().stream()
            .sorted(java.util.Comparator.comparing(StoredTransaction::transactionKey))
            .toList();
    }

    /**
     * 그 주문으로 들어온 create 호출 횟수. 중복 create 로 거래가 늘지 않아도 이 값은 올라간다.
     * 오류를 돌려줬다고 호출 사실을 지우지 않는다.
     */
    public int createCount(String providerOrderId) {
        AtomicInteger count = createCounts.get(providerOrderId);
        return count == null ? 0 : count.get();
    }

    public List<ReceivedRequest> receivedRequests() {
        return List.copyOf(received);
    }

    public long createCount() {
        return received.stream().filter(request -> "POST".equals(request.method()) && PAYMENTS_PATH.equals(request.path())).count();
    }

    public void reset() {
        releaseResponses();
        clearResponseDrops();
        received.clear();
        transactionResponses.clear();
        listResponses.clear();
        createResponse = new Response(202, null);
        transactionStore = false;
        transactions.clear();
        createCounts.clear();
        sequence.set(0);
    }

    @Override
    public void close() {
        releaseResponses();
        clearResponseDrops();
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        ReceivedRequest request = record(exchange);
        try {
            if (!hold.await(HOLD_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("테스트 PG 서버: 보류 해제를 기다리다 시간이 지났다.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        if (transactionStore && isCreate(request)) {
            String providerOrderId = fieldOf(request.body(), "orderId");
            Response created = handleStatefulCreate(request, providerOrderId);
            if (created == null) {
                // 거래는 이미 SUCCESS 로 저장했다. 여기서는 응답을 쓰지 않아 호출자가 read timeout 을 맞게 한다.
                awaitDropRelease(providerOrderId);
                exchange.close();
                return;
            }
            respond(exchange, created);
            return;
        }
        respond(exchange, route(request));
    }

    private static boolean isCreate(ReceivedRequest request) {
        return "POST".equals(request.method()) && PAYMENTS_PATH.equals(request.path());
    }

    /**
     * 실행 순서가 계약이다.
     * ① create 수신 횟수 기록 → ② 거래 없으면 1건 생성(있으면 재생성하지 않음) → ③ 장애 대상이면 SUCCESS 로 확정 저장 → ④ 응답 유실(null 반환).
     *
     * @return 보낼 응답. null 이면 응답을 유실시켜야 한다는 뜻이다.
     */
    private Response handleStatefulCreate(ReceivedRequest request, String providerOrderId) {
        long amount = longFieldOf(request.body(), "amount");
        createCounts.computeIfAbsent(providerOrderId, key -> new AtomicInteger()).incrementAndGet();
        StoredTransaction transaction = transactions.computeIfAbsent(providerOrderId,
            key -> new StoredTransaction(String.format(TRANSACTION_KEY_FORMAT, sequence.incrementAndGet()), key, amount, PENDING));
        if (responseDrops.containsKey(providerOrderId)) {
            transactions.put(providerOrderId, withStatus(transaction, SUCCESS));
            return null;
        }
        return new Response(202, acceptedBody(transaction));
    }

    private void awaitDropRelease(String providerOrderId) {
        CountDownLatch latch = responseDrops.get(providerOrderId);
        if (latch == null) {
            return;
        }
        try {
            latch.await(HOLD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static StoredTransaction withStatus(StoredTransaction transaction, String status) {
        return new StoredTransaction(transaction.transactionKey(), transaction.providerOrderId(), transaction.amount(), status);
    }

    private static String acceptedBody(StoredTransaction transaction) {
        return MAPPER.createObjectNode()
            .put("transactionKey", transaction.transactionKey())
            .put("orderId", transaction.providerOrderId())
            .put("amount", transaction.amount())
            .put("status", PENDING)
            .toString();
    }

    private String storedListBody(String providerOrderId) {
        StoredTransaction transaction = transactions.get(providerOrderId);
        if (transaction == null) {
            return "[]";
        }
        return MAPPER.createArrayNode().add(MAPPER.createObjectNode()
            .put("transactionKey", transaction.transactionKey())
            .put("orderId", transaction.providerOrderId())
            .put("amount", transaction.amount())
            .put("status", transaction.status())).toString();
    }

    private static String fieldOf(String body, String field) {
        JsonNode value = readBody(body).get(field);
        return value == null || !value.isTextual() ? null : value.asText();
    }

    private static long longFieldOf(String body, String field) {
        JsonNode value = readBody(body).get(field);
        return value == null || !value.isIntegralNumber() ? 0L : value.asLong();
    }

    private static JsonNode readBody(String body) {
        try {
            return MAPPER.readTree(body);
        } catch (IOException e) {
            throw new IllegalStateException("테스트 PG 서버: create 본문이 JSON 이 아니다.");
        }
    }

    private Response route(ReceivedRequest request) {
        if ("POST".equals(request.method()) && PAYMENTS_PATH.equals(request.path())) {
            return createResponse;
        }
        if ("GET".equals(request.method()) && PAYMENTS_PATH.equals(request.path())) {
            String orderId = request.query().get("orderId");
            Response stubbed = listResponses.get(orderId);
            if (stubbed != null) {
                return stubbed;
            }
            // 거래 보관 모드에서는 정해진 응답이 없으면 보관된 거래로 답한다. 복구 단계의 권위 조회가 이 경로를 쓴다.
            return transactionStore ? new Response(200, storedListBody(orderId)) : new Response(200, "[]");
        }
        if ("GET".equals(request.method()) && request.path().startsWith(PAYMENTS_PATH + "/")) {
            String transactionKey = request.path().substring(PAYMENTS_PATH.length() + 1);
            return transactionResponses.getOrDefault(transactionKey, new Response(404, null));
        }
        return new Response(404, null);
    }

    private ReceivedRequest record(HttpExchange exchange) throws IOException {
        URI uri = exchange.getRequestURI();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, List<String>> headers = new ConcurrentHashMap<>();
        exchange.getRequestHeaders().forEach((name, values) -> headers.put(name.toLowerCase(), List.copyOf(values)));
        ReceivedRequest request = new ReceivedRequest(
            exchange.getRequestMethod(), uri.getRawPath(), uri.getRawQuery(), decodeQuery(uri.getRawQuery()), headers, body);
        received.add(request);
        return request;
    }

    private static Map<String, String> decodeQuery(String rawQuery) {
        Map<String, String> query = new ConcurrentHashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return query;
        }
        for (String pair : rawQuery.split("&")) {
            int at = pair.indexOf('=');
            String name = at < 0 ? pair : pair.substring(0, at);
            String value = at < 0 ? "" : pair.substring(at + 1);
            query.put(URLDecoder.decode(name, StandardCharsets.UTF_8), URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return query;
    }

    private static void respond(HttpExchange exchange, Response response) throws IOException {
        if (response.body() == null) {
            exchange.sendResponseHeaders(response.status(), -1);
            exchange.close();
            return;
        }
        byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(response.status(), bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}