package com.loopers.support.pg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 테스트 전용 로컬 PG (과제 2절 계약, plan.md 7장). 저장소에 PG가 없어 테스트 안에서 HTTP 서버로 띄운다.
 *
 * <p>계약
 * <ul>
 *   <li>{@code POST /api/v1/payments} body {@code {orderId, cardType, cardNo, amount, callbackUrl}} → 거래 생성, 응답 거래 객체(status PENDING)</li>
 *   <li>{@code GET /api/v1/payments/{transactionKey}} → 거래 객체, 없으면 404</li>
 *   <li>{@code GET /api/v1/payments?orderId=} → 거래 객체 배열 (0건도 정상)</li>
 *   <li>세 경로 모두 헤더 {@code X-USER-ID: 135135}가 없거나 다르면 401</li>
 *   <li>멱등 키를 받지 않고, 같은 orderId의 새 POST도 그대로 새 거래를 만든다</li>
 * </ul>
 * JSON 모양은 감싸지 않은 그대로다 — 거래 객체 {@code {transactionKey, orderId, amount, status, reason}}, 목록은 그 배열.
 * 카드 번호는 검사만 하고 저장·출력하지 않는다.
 *
 * <p>호출마다 동작을 미리 정할 수 있다 (한 번 쓰면 사라지는 큐): 거절, 생성 뒤 응답 지연·500·깨진 본문, 생성 없이 500, 조회 500·지연.
 */
public final class LocalTestPgServer implements AutoCloseable {

    public static final String USER_ID = "135135";
    private static final String USER_HEADER = "X-USER-ID";
    private static final String PAYMENTS = "/api/v1/payments";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final HttpClient callbackClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(1))
        .build();

    private final Map<String, Transaction> transactions = Collections.synchronizedMap(new LinkedHashMap<>());
    private final Queue<CreateBehavior> createBehaviors = new ConcurrentLinkedQueue<>();
    private final Queue<LookupBehavior> lookupBehaviors = new ConcurrentLinkedQueue<>();
    private final Queue<CreateRecord> createRecords = new ConcurrentLinkedQueue<>();
    private final AtomicInteger createCalls = new AtomicInteger();
    private final AtomicInteger lookupCalls = new AtomicInteger();
    private final AtomicInteger sequence = new AtomicInteger();

    private LocalTestPgServer(HttpServer server) {
        this.server = server;
        server.createContext(PAYMENTS, this::handle);
        server.setExecutor(executor);
    }

    /**
     * 127.0.0.1의 빈 포트로 띄운다.
     */
    public static LocalTestPgServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            LocalTestPgServer pg = new LocalTestPgServer(server);
            server.start();
            return pg;
        } catch (IOException e) {
            throw new IllegalStateException("로컬 테스트 PG를 띄우지 못했습니다", e);
        }
    }

    public void stop() {
        server.stop(0);
        executor.shutdownNow();
    }

    @Override
    public void close() {
        stop();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + port();
    }

    /**
     * 거래·예약 동작·기록·카운터를 모두 비운다. 테스트마다 부른다.
     */
    public void reset() {
        transactions.clear();
        createBehaviors.clear();
        lookupBehaviors.clear();
        createRecords.clear();
        createCalls.set(0);
        lookupCalls.set(0);
    }

    // ---- 다음 생성 호출의 동작 (호출 하나에 하나씩 소비) ----

    /** 거래를 만들지 않고 400으로 거절한다. */
    public void rejectNextCreate() {
        createBehaviors.add(new CreateBehavior(CreateMode.REJECT, Duration.ZERO));
    }

    /** 거래를 만든 뒤 응답을 delay만큼 늦게 보낸다. 클라이언트 읽기 timeout보다 길면 "생성됐지만 응답 유실"이다. */
    public void delayNextCreateResponse(Duration delay) {
        createBehaviors.add(new CreateBehavior(CreateMode.CREATE_THEN_DELAY, delay));
    }

    /** 거래를 만든 뒤 500으로 답한다. */
    public void failNextCreateAfterCreating() {
        createBehaviors.add(new CreateBehavior(CreateMode.CREATE_THEN_500, Duration.ZERO));
    }

    /** 거래를 만들지 않고 500으로 답한다. */
    public void failNextCreateWithoutCreating() {
        createBehaviors.add(new CreateBehavior(CreateMode.FAIL_500, Duration.ZERO));
    }

    /** 거래를 만든 뒤 200과 해석할 수 없는 본문으로 답한다. */
    public void garbleNextCreateResponse() {
        createBehaviors.add(new CreateBehavior(CreateMode.CREATE_THEN_GARBAGE, Duration.ZERO));
    }

    // ---- 다음 조회 호출의 동작 ----

    /** 다음 조회 times번을 500으로 답한다. */
    public void failNextLookups(int times) {
        for (int i = 0; i < times; i++) {
            lookupBehaviors.add(new LookupBehavior(true, Duration.ZERO));
        }
    }

    /** 다음 조회 times번을 delay만큼 늦게 정상 응답한다. */
    public void delayNextLookups(int times, Duration delay) {
        for (int i = 0; i < times; i++) {
            lookupBehaviors.add(new LookupBehavior(false, delay));
        }
    }

    // ---- 거래 결과와 callback ----

    /** 거래의 최종 상태를 정한다 (SUCCESS·FAILED). */
    public void complete(String transactionKey, String status, String reason) {
        Transaction current = transaction(transactionKey)
            .orElseThrow(() -> new IllegalArgumentException("없는 거래: " + transactionKey));
        transactions.put(transactionKey, current.withStatus(status, reason));
    }

    /**
     * 저장된 callbackUrl로 {@code {transactionKey, orderId, status}}를 POST하고 HTTP 상태 코드를 돌려준다.
     * 같은 거래로 여러 번 부르면 중복 callback이다.
     */
    public int sendCallback(String transactionKey) {
        Transaction tx = transaction(transactionKey)
            .orElseThrow(() -> new IllegalArgumentException("없는 거래: " + transactionKey));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("transactionKey", tx.transactionKey());
        body.put("orderId", tx.orderId());
        body.put("status", tx.status());
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(tx.callbackUrl()))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                .build();
            return callbackClient.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (IOException e) {
            throw new IllegalStateException("callback을 보내지 못했습니다: " + tx.callbackUrl(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    // ---- 관찰 ----

    /** 생성 요청이 도착한 횟수 (거절·실패 포함). */
    public int createCalls() {
        return createCalls.get();
    }

    /** 실제로 만들어진 거래 수. */
    public int transactionsCreated() {
        return transactions.size();
    }

    /** 조회 요청이 도착한 횟수 (단건·목록 합계). */
    public int lookupCalls() {
        return lookupCalls.get();
    }

    public Optional<Transaction> transaction(String transactionKey) {
        return Optional.ofNullable(transactions.get(transactionKey));
    }

    public List<Transaction> transactionsOf(String orderId) {
        synchronized (transactions) {
            return transactions.values().stream().filter(tx -> tx.orderId().equals(orderId)).toList();
        }
    }

    /** 도착한 생성 요청의 헤더·본문 기록 (카드 번호 제외). */
    public List<CreateRecord> createRecords() {
        return List.copyOf(createRecords);
    }

    // ---- HTTP 처리 ----

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            if ("POST".equals(method) && PAYMENTS.equals(path)) {
                handleCreate(exchange);
            } else if ("GET".equals(method) && PAYMENTS.equals(path)) {
                handleList(exchange);
            } else if ("GET".equals(method) && path.startsWith(PAYMENTS + "/")) {
                handleGet(exchange, path.substring(PAYMENTS.length() + 1));
            } else {
                respond(exchange, 404, "{\"message\":\"없는 경로\"}");
            }
        } catch (IOException e) {
            // 클라이언트가 timeout으로 먼저 끊으면 응답 쓰기가 실패한다. 테스트가 일부러 만든 상황이다.
        }
    }

    private void handleCreate(HttpExchange exchange) throws IOException {
        createCalls.incrementAndGet();
        String userId = exchange.getRequestHeaders().getFirst(USER_HEADER);
        JsonNode body = readJson(exchange.getRequestBody());
        createRecords.add(CreateRecord.of(userId, body));
        if (!USER_ID.equals(userId)) {
            respond(exchange, 401, "{\"message\":\"X-USER-ID가 없거나 다릅니다\"}");
            return;
        }
        CreateBehavior behavior = Optional.ofNullable(createBehaviors.poll()).orElse(CreateBehavior.NORMAL);
        if (behavior.mode() == CreateMode.REJECT || !isValidCreate(body)) {
            respond(exchange, 400, "{\"message\":\"요청을 거절했습니다\"}");
            return;
        }
        if (behavior.mode() == CreateMode.FAIL_500) {
            respond(exchange, 500, "{\"message\":\"서버 오류\"}");
            return;
        }
        Transaction tx = new Transaction(
            nextTransactionKey(),
            body.path("orderId").asText(),
            body.path("amount").asLong(),
            "PENDING",
            null,
            body.path("callbackUrl").asText(null));
        transactions.put(tx.transactionKey(), tx);
        switch (behavior.mode()) {
            case CREATE_THEN_500 -> respond(exchange, 500, "{\"message\":\"서버 오류\"}");
            case CREATE_THEN_GARBAGE -> respond(exchange, 200, "{\"transactionKey\":");
            case CREATE_THEN_DELAY -> {
                sleep(behavior.delay());
                respond(exchange, 200, toJson(tx));
            }
            default -> respond(exchange, 200, toJson(tx));
        }
    }

    private void handleGet(HttpExchange exchange, String transactionKey) throws IOException {
        if (!beforeLookup(exchange)) {
            return;
        }
        Optional<Transaction> tx = transaction(URLDecoder.decode(transactionKey, StandardCharsets.UTF_8));
        if (tx.isEmpty()) {
            respond(exchange, 404, "{\"message\":\"거래가 없습니다\"}");
            return;
        }
        respond(exchange, 200, toJson(tx.get()));
    }

    private void handleList(HttpExchange exchange) throws IOException {
        if (!beforeLookup(exchange)) {
            return;
        }
        String orderId = queryParam(exchange.getRequestURI().getRawQuery(), "orderId");
        if (orderId == null || orderId.isBlank()) {
            respond(exchange, 400, "{\"message\":\"orderId가 필요합니다\"}");
            return;
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (Transaction tx : transactionsOf(orderId)) {
            list.add(tx.toBody());
        }
        respond(exchange, 200, JSON.writeValueAsString(list));
    }

    /** 조회 공통: 호출 수를 세고, 헤더를 확인하고, 예약된 500·지연을 적용한다. 계속 처리하면 true. */
    private boolean beforeLookup(HttpExchange exchange) throws IOException {
        lookupCalls.incrementAndGet();
        if (!USER_ID.equals(exchange.getRequestHeaders().getFirst(USER_HEADER))) {
            respond(exchange, 401, "{\"message\":\"X-USER-ID가 없거나 다릅니다\"}");
            return false;
        }
        LookupBehavior behavior = lookupBehaviors.poll();
        if (behavior == null) {
            return true;
        }
        if (behavior.fail()) {
            respond(exchange, 500, "{\"message\":\"서버 오류\"}");
            return false;
        }
        sleep(behavior.delay());
        return true;
    }

    private static boolean isValidCreate(JsonNode body) {
        String orderId = body.path("orderId").asText("");
        JsonNode amount = body.path("amount");
        return orderId.length() >= 6
            && amount.isIntegralNumber() && amount.asLong() > 0
            && !body.path("cardType").asText("").isBlank()
            && !body.path("cardNo").asText("").isBlank()
            && !body.path("callbackUrl").asText("").isBlank();
    }

    private String nextTransactionKey() {
        String date = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        return date + ":TR:" + suffix + sequence.incrementAndGet();
    }

    private static JsonNode readJson(InputStream in) {
        try {
            return JSON.readTree(in.readAllBytes());
        } catch (IOException e) {
            return JSON.createObjectNode();
        }
    }

    private static String toJson(Transaction tx) throws IOException {
        return JSON.writeValueAsString(tx.toBody());
    }

    private static String queryParam(String rawQuery, String name) {
        if (rawQuery == null) {
            return null;
        }
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) {
                return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void sleep(Duration delay) {
        if (delay.isZero()) {
            return;
        }
        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---- 값 ----

    /** PG에 저장된 거래. 카드 번호는 없다. */
    public record Transaction(String transactionKey, String orderId, long amount, String status, String reason, String callbackUrl) {

        Transaction withStatus(String newStatus, String newReason) {
            return new Transaction(transactionKey, orderId, amount, newStatus, newReason, callbackUrl);
        }

        Map<String, Object> toBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("transactionKey", transactionKey);
            body.put("orderId", orderId);
            body.put("amount", amount);
            body.put("status", status);
            body.put("reason", reason);
            return body;
        }
    }

    /** 도착한 생성 요청. 카드 번호는 남기지 않고 왔는지만 기록한다. */
    public record CreateRecord(String userId, String orderId, String cardType, boolean cardNoPresent, long amount, String callbackUrl) {

        static CreateRecord of(String userId, JsonNode body) {
            return new CreateRecord(
                userId,
                body.path("orderId").asText(null),
                body.path("cardType").asText(null),
                !body.path("cardNo").asText("").isBlank(),
                body.path("amount").asLong(),
                body.path("callbackUrl").asText(null));
        }
    }

    private enum CreateMode { NORMAL, REJECT, CREATE_THEN_DELAY, CREATE_THEN_500, FAIL_500, CREATE_THEN_GARBAGE }

    private record CreateBehavior(CreateMode mode, Duration delay) {
        static final CreateBehavior NORMAL = new CreateBehavior(CreateMode.NORMAL, Duration.ZERO);
    }

    private record LookupBehavior(boolean fail, Duration delay) {}
}
