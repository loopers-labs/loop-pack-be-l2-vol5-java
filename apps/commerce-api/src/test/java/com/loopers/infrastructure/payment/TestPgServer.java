package com.loopers.infrastructure.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class TestPgServer implements AutoCloseable {
    static final String PAYMENTS_PATH = "/api/v1/payments";
    static final String REJECT_CODE = "TEST_PG_REJECTED_BEFORE_CREATE";

    public enum CreateBehavior {
        ACCEPT_PENDING,
        ACCEPT_SUCCESS,
        ACCEPT_FAILED,
        ACCEPT_WITH_200,
        ACCEPT_BLANK_TRANSACTION_KEY,
        ACCEPT_UNKNOWN_STATUS,
        ACCEPT_AMOUNT_MISMATCH,
        ACCEPT_ORDER_ID_MISMATCH,
        ACCEPT_AMOUNT_AS_STRING,
        ACCEPT_BROKEN_JSON,
        REJECT_BEFORE_CREATE,
        REJECT_WITHOUT_BODY,
        REJECT_TRANSACTION_CREATED,
        REJECT_OTHER_CODE,
        REJECT_AMOUNT_MISMATCH,
        ERROR_400,
        ERROR_500_AFTER_CREATE,
        ERROR_500_WITHOUT_CREATE,
        ERROR_500_WITH_REJECT_BODY,
        ERROR_500_ECHO_REQUEST,
        DELAY_AFTER_CREATE,
        DROP_CONNECTION_AFTER_CREATE
    }

    public enum LookupBehavior {
        NORMAL,
        ERROR_500,
        DELAY,
        BROKEN_JSON,
        WRONG_ORDER_ID
    }

    record RecordedRequest(String method, String rawPath, String rawQuery, Map<String, List<String>> headers, String body) {
        String header(String name) {
            return headers.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .map(entry -> entry.getValue().get(0))
                .findFirst()
                .orElse(null);
        }
    }

    public record StoredTransaction(String transactionKey, String orderId, long amount, String status) {
    }

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String expectedUserId;
    private final Duration delay;
    private final HttpServer server;
    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
    private final Map<String, StoredTransaction> transactions = new ConcurrentHashMap<>();
    private final AtomicInteger keySequence = new AtomicInteger();
    private volatile CreateBehavior createBehavior = CreateBehavior.ACCEPT_PENDING;
    private volatile LookupBehavior lookupBehavior = LookupBehavior.NORMAL;

    public TestPgServer(String expectedUserId, Duration delay) throws IOException {
        this.expectedUserId = expectedUserId;
        this.delay = delay;
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        this.server.createContext(PAYMENTS_PATH, this::handle);
        this.server.setExecutor(executor);
        this.server.start();
    }

    public URI baseUrl() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    public void createBehavior(CreateBehavior behavior) {
        this.createBehavior = behavior;
    }

    public void lookupBehavior(LookupBehavior behavior) {
        this.lookupBehavior = behavior;
    }

    public String addTransaction(String orderId, long amount, String status) {
        return store(orderId, amount, status).transactionKey();
    }

    public void changeStatus(String key, String status) {
        StoredTransaction current = transactions.get(key);
        replaceTransaction(key, current.orderId(), current.amount(), status);
    }

    public void removeTransaction(String key) {
        transactions.remove(key);
    }

    public void replaceTransaction(String key, String orderId, long amount, String status) {
        transactions.put(key, new StoredTransaction(key, orderId, amount, status));
    }

    public List<StoredTransaction> transactions() {
        return List.copyOf(transactions.values());
    }

    List<RecordedRequest> requests() {
        return List.copyOf(requests);
    }

    public long createCalls() {
        return requests.stream().filter(request -> request.method().equals("POST")).count();
    }

    public long lookupCalls() {
        return requests.stream().filter(request -> request.method().equals("GET")).count();
    }

    @Override
    public void close() throws InterruptedException {
        server.stop(0);
        executor.shutdownNow();
        executor.awaitTermination(5, TimeUnit.SECONDS);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            URI uri = exchange.getRequestURI();
            requests.add(new RecordedRequest(exchange.getRequestMethod(), uri.getRawPath(), uri.getRawQuery(),
                Map.copyOf(exchange.getRequestHeaders()), body));
            if (!expectedUserId.equals(exchange.getRequestHeaders().getFirst("X-USER-ID"))) {
                send(exchange, 400, "{\"code\":\"MISSING_USER\"}");
                return;
            }
            if (exchange.getRequestMethod().equals("POST") && uri.getRawPath().equals(PAYMENTS_PATH)) {
                handleCreate(exchange, objectMapper.readTree(body));
            } else if (exchange.getRequestMethod().equals("GET")) {
                handleLookup(exchange, uri);
            } else {
                send(exchange, 405, null);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void handleCreate(HttpExchange exchange, JsonNode request) throws IOException, InterruptedException {
        String orderId = request.path("orderId").asText();
        long amount = request.path("amount").asLong();
        switch (createBehavior) {
            case ACCEPT_PENDING -> send(exchange, 202, transactionJson(store(orderId, amount, "PENDING")));
            case ACCEPT_SUCCESS -> send(exchange, 202, transactionJson(store(orderId, amount, "SUCCESS")));
            case ACCEPT_FAILED -> send(exchange, 202, transactionJson(store(orderId, amount, "FAILED")));
            case ACCEPT_WITH_200 -> send(exchange, 200, transactionJson(store(orderId, amount, "PENDING")));
            case ACCEPT_BLANK_TRANSACTION_KEY -> send(exchange, 202,
                transactionJson(new StoredTransaction(" ", orderId, amount, "PENDING")));
            case ACCEPT_UNKNOWN_STATUS -> send(exchange, 202, transactionJson(store(orderId, amount, "CANCELED")));
            case ACCEPT_AMOUNT_MISMATCH -> {
                StoredTransaction stored = store(orderId, amount, "PENDING");
                send(exchange, 202, transactionJson(
                    new StoredTransaction(stored.transactionKey(), orderId, amount + 1, "PENDING")));
            }
            case ACCEPT_ORDER_ID_MISMATCH -> {
                StoredTransaction stored = store(orderId, amount, "PENDING");
                send(exchange, 202, transactionJson(
                    new StoredTransaction(stored.transactionKey(), orderId + "-X", amount, "PENDING")));
            }
            case ACCEPT_AMOUNT_AS_STRING -> {
                StoredTransaction stored = store(orderId, amount, "PENDING");
                ObjectNode node = (ObjectNode) objectMapper.readTree(transactionJson(stored));
                node.put("amount", String.valueOf(amount));
                send(exchange, 202, node.toString());
            }
            case ACCEPT_BROKEN_JSON -> {
                store(orderId, amount, "PENDING");
                send(exchange, 202, "{\"transactionKey\":\"TR-");
            }
            case REJECT_BEFORE_CREATE -> send(exchange, 422, rejectJson(orderId, amount, REJECT_CODE, false));
            case REJECT_WITHOUT_BODY -> send(exchange, 422, null);
            case REJECT_TRANSACTION_CREATED -> {
                store(orderId, amount, "FAILED");
                send(exchange, 422, rejectJson(orderId, amount, REJECT_CODE, true));
            }
            case REJECT_OTHER_CODE -> send(exchange, 422, rejectJson(orderId, amount, "INVALID_CARD", false));
            case REJECT_AMOUNT_MISMATCH -> send(exchange, 422, rejectJson(orderId, amount + 1, REJECT_CODE, false));
            case ERROR_400 -> send(exchange, 400, "{\"code\":\"BAD_REQUEST\"}");
            case ERROR_500_AFTER_CREATE -> {
                store(orderId, amount, "PENDING");
                send(exchange, 500, "{\"code\":\"INTERNAL_ERROR\"}");
            }
            case ERROR_500_WITHOUT_CREATE -> send(exchange, 500, null);
            case ERROR_500_WITH_REJECT_BODY -> send(exchange, 500, rejectJson(orderId, amount, REJECT_CODE, false));
            // 받은 본문(카드 번호 포함)을 그대로 돌려줘, 어댑터가 응답 본문을 결과·로그에 옮기지 않는지 확인한다.
            case ERROR_500_ECHO_REQUEST -> send(exchange, 500, request.toString());
            case DELAY_AFTER_CREATE -> {
                StoredTransaction stored = store(orderId, amount, "PENDING");
                Thread.sleep(delay.toMillis());
                send(exchange, 202, transactionJson(stored));
            }
            // 거래를 만든 뒤 응답 헤더 없이 연결을 닫는다(HttpExchange.close가 연결을 끊는다).
            case DROP_CONNECTION_AFTER_CREATE -> store(orderId, amount, "PENDING");
            default -> throw new IllegalStateException("지원하지 않는 동작: " + createBehavior);
        }
    }

    private void handleLookup(HttpExchange exchange, URI uri) throws IOException, InterruptedException {
        switch (lookupBehavior) {
            case ERROR_500 -> {
                send(exchange, 500, null);
                return;
            }
            case DELAY -> Thread.sleep(delay.toMillis());
            case BROKEN_JSON -> {
                send(exchange, 200, "[{\"transactionKey\":");
                return;
            }
            default -> {
            }
        }
        String rawPath = uri.getRawPath();
        if (rawPath.length() > PAYMENTS_PATH.length()) {
            String key = URLDecoder.decode(rawPath.substring(PAYMENTS_PATH.length() + 1), StandardCharsets.UTF_8);
            StoredTransaction stored = transactions.get(key);
            if (stored == null) {
                send(exchange, 404, "{\"code\":\"NOT_FOUND\"}");
                return;
            }
            send(exchange, 200, transactionJson(maybeWrongOrderId(stored)));
            return;
        }
        String orderId = queryParameter(uri.getRawQuery(), "orderId");
        ArrayNode array = objectMapper.createArrayNode();
        transactions.values().stream()
            .filter(stored -> stored.orderId().equals(orderId))
            .sorted((left, right) -> left.transactionKey().compareTo(right.transactionKey()))
            .forEach(stored -> array.add(readTree(transactionJson(maybeWrongOrderId(stored)))));
        send(exchange, 200, array.toString());
    }

    private StoredTransaction maybeWrongOrderId(StoredTransaction stored) {
        if (lookupBehavior != LookupBehavior.WRONG_ORDER_ID) {
            return stored;
        }
        return new StoredTransaction(stored.transactionKey(), stored.orderId() + "-X", stored.amount(), stored.status());
    }

    private StoredTransaction store(String orderId, long amount, String status) {
        String key = "TR-" + String.format("%06d", keySequence.incrementAndGet());
        StoredTransaction stored = new StoredTransaction(key, orderId, amount, status);
        transactions.put(key, stored);
        return stored;
    }

    private String transactionJson(StoredTransaction stored) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("transactionKey", stored.transactionKey());
        node.put("orderId", stored.orderId());
        node.put("amount", stored.amount());
        node.put("status", stored.status());
        return node.toString();
    }

    private String rejectJson(String orderId, long amount, String code, boolean transactionCreated) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("code", code);
        node.put("orderId", orderId);
        node.put("amount", amount);
        node.put("transactionCreated", transactionCreated);
        return node.toString();
    }

    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String queryParameter(String rawQuery, String name) {
        if (rawQuery == null) {
            return null;
        }
        for (String pair : rawQuery.split("&")) {
            int separator = pair.indexOf('=');
            if (separator > 0 && pair.substring(0, separator).equals(name)) {
                return URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        if (body == null) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
