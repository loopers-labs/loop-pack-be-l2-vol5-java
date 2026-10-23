package com.loopers.infrastructure.payment.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Assignment-defined PG contract on a real loopback socket, not a real payment vendor.
 * Its transaction map is independent of Commerce DB. Captured evidence never retains a card number or raw body.
 */
public final class LocalTestPg implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PAYMENTS = "/api/v1/payments";
    private static final int MAX_BODY_BYTES = 16_384;
    private final HttpServer server;
    private final ThreadPoolExecutor workers;
    private final Map<String, Transaction> transactions = new ConcurrentHashMap<>();
    private final List<CapturedRequest> requests = new CopyOnWriteArrayList<>();
    private final AtomicInteger creates = new AtomicInteger();
    private final AtomicInteger lookups = new AtomicInteger();
    private final AtomicInteger sequence = new AtomicInteger();
    private final AtomicReference<CreateMode> nextMode = new AtomicReference<>(CreateMode.NORMAL);
    private final AtomicReference<String> nextCreateStatus = new AtomicReference<>("PENDING");
    private final AtomicReference<Reply> nextReply = new AtomicReference<>();
    private volatile CountDownLatch created = new CountDownLatch(1);
    private volatile CountDownLatch requestReceived = new CountDownLatch(1);
    private volatile CountDownLatch bodyStarted = new CountDownLatch(1);
    private volatile CountDownLatch responsesReleased = new CountDownLatch(1);
    private volatile byte[] expectedCardDigest = digest("0000-0000-0000-0000");

    public LocalTestPg() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 16);
        workers = new ThreadPoolExecutor(4, 4, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(32),
            task -> Thread.ofPlatform().daemon(true).name("local-test-pg").unstarted(task));
        server.setExecutor(workers);
        server.createContext("/", this::handle);
        server.start();
    }

    public URI baseUri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    public URI callbackUri() {
        return baseUri().resolve("/api/v1/payments/callback");
    }

    public int createCalls() {
        return creates.get();
    }

    public int lookupCalls() {
        return lookups.get();
    }

    public int transactionCount() {
        return transactions.size();
    }

    public List<CapturedRequest> requests() {
        return List.copyOf(requests);
    }

    /** Between completed tests only: discard this fixture's transactions, not any application database state. */
    public void reset() {
        transactions.clear();
        requests.clear();
        creates.set(0);
        lookups.set(0);
        sequence.set(0);
        nextMode.set(CreateMode.NORMAL);
        nextCreateStatus.set("PENDING");
        nextReply.set(null);
        created = new CountDownLatch(1);
        requestReceived = new CountDownLatch(1);
        bodyStarted = new CountDownLatch(1);
        responsesReleased = new CountDownLatch(1);
        expectedCardDigest = digest("0000-0000-0000-0000");
    }

    public void setStatus(String transactionKey, String status) {
        if (!Set.of("PENDING", "SUCCESS", "FAILED").contains(status)) {
            throw new IllegalArgumentException("Unknown fixture transaction status");
        }
        transactions.compute(transactionKey, (key, previous) -> {
            if (previous == null) {
                throw new IllegalArgumentException("Fixture transaction does not exist");
            }
            return new Transaction(key, previous.orderId(), previous.amount(), status);
        });
    }

    public void nextCreateMode(CreateMode mode) {
        created = new CountDownLatch(1);
        responsesReleased = new CountDownLatch(1);
        nextMode.set(Objects.requireNonNull(mode));
    }

    /** Deterministic terminal create responses still persist a real fixture transaction before responding. */
    public void nextCreateStatus(String status) {
        if (!Set.of("PENDING", "SUCCESS", "FAILED").contains(status)) {
            throw new IllegalArgumentException("Unknown fixture transaction status");
        }
        nextCreateStatus.set(status);
    }

    /** Test-only payload equality evidence; this digest is not stored in the application or used for idempotency. */
    public void expectedCard(String syntheticCard) {
        expectedCardDigest = digest(Objects.requireNonNull(syntheticCard));
    }

    public boolean awaitCreate(Duration timeout) throws InterruptedException {
        return created.await(timeout.toNanos(), TimeUnit.NANOSECONDS);
    }

    public void releaseResponses() {
        responsesReleased.countDown();
    }

    /** One-shot protocol-failure fixture. Capture/count happen first; no transaction is created by this override. */
    public void replyNext(int status, String json) {
        nextReply.set(new Reply(status, Objects.requireNonNull(json), null, Hold.NONE));
    }

    public void replyNextHeld(int status, String json) {
        requestReceived = new CountDownLatch(1);
        responsesReleased = new CountDownLatch(1);
        nextReply.set(new Reply(status, Objects.requireNonNull(json), null, Hold.BEFORE_HEADERS));
    }

    public boolean awaitRequest(Duration timeout) throws InterruptedException {
        return requestReceived.await(timeout.toNanos(), TimeUnit.NANOSECONDS);
    }

    public void replyNextHeldBody(int status, String json) {
        if (Objects.requireNonNull(json).getBytes(StandardCharsets.UTF_8).length < 2) {
            throw new IllegalArgumentException("A held body needs a first byte and remaining bytes");
        }
        requestReceived = new CountDownLatch(1);
        bodyStarted = new CountDownLatch(1);
        responsesReleased = new CountDownLatch(1);
        nextReply.set(new Reply(status, json, null, Hold.AFTER_FIRST_BODY_BYTE));
    }

    public boolean awaitBody(Duration timeout) throws InterruptedException {
        return bodyStarted.await(timeout.toNanos(), TimeUnit.NANOSECONDS);
    }

    public void replyNextRedirect(URI location) {
        nextReply.set(new Reply(307, "", Objects.requireNonNull(location), Hold.NONE));
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            JsonNode body = readBody(exchange);
            capture(exchange, body);
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            if ("POST".equals(method) && PAYMENTS.equals(path)) {
                creates.incrementAndGet();
            } else if ("GET".equals(method) && (PAYMENTS.equals(path) || path.startsWith(PAYMENTS + "/"))) {
                lookups.incrementAndGet();
            }
            requestReceived.countDown();

            Reply reply = nextReply.getAndSet(null);
            if (reply != null) {
                if (reply.hold() == Hold.BEFORE_HEADERS && !awaitResponseRelease()) {
                    return;
                }
                if (reply.location() != null) {
                    exchange.getResponseHeaders().set("Location", reply.location().toString());
                }
                if (reply.hold() == Hold.AFTER_FIRST_BODY_BYTE) {
                    sendHeldBody(exchange, reply.status(), reply.json());
                } else {
                    send(exchange, reply.status(), reply.json());
                }
            } else if (!"135135".equals(exchange.getRequestHeaders().getFirst("X-USER-ID"))) {
                send(exchange, 401, "{}");
            } else if ("POST".equals(method) && PAYMENTS.equals(path)) {
                create(exchange, body);
            } else if ("GET".equals(method) && PAYMENTS.equals(path)) {
                String orderId = orderQuery(exchange.getRequestURI());
                List<Transaction> matching = transactions.values().stream()
                    .filter(transaction -> transaction.orderId().equals(orderId))
                    .sorted(Comparator.comparing(Transaction::transactionKey)).toList();
                send(exchange, 200, JSON.writeValueAsString(matching));
            } else if ("GET".equals(method) && path.startsWith(PAYMENTS + "/")) {
                Transaction transaction = transactions.get(path.substring(PAYMENTS.length() + 1));
                send(exchange, transaction == null ? 404 : 200,
                    transaction == null ? "{}" : JSON.writeValueAsString(transaction));
            } else {
                send(exchange, 404, "{}");
            }
        }
    }

    private void create(HttpExchange exchange, JsonNode body) throws IOException {
        if (!validCreate(body)) {
            send(exchange, 400, "{}");
            return;
        }
        String orderId = body.get("orderId").textValue();
        long amount = body.get("amount").longValue();
        CreateMode mode = nextMode.getAndSet(CreateMode.NORMAL);
        String initialStatus = nextCreateStatus.getAndSet("PENDING");
        if (mode == CreateMode.REJECT_BEFORE_CREATE) {
            send(exchange, 422, JSON.writeValueAsString(Map.of("code", "TEST_PG_REJECTED_BEFORE_CREATE",
                "orderId", orderId, "amount", amount, "transactionCreated", false)));
            return;
        }
        String key = "TEST-TX-" + sequence.incrementAndGet();
        Transaction transaction = new Transaction(key, orderId, amount, initialStatus);
        transactions.put(key, transaction);
        created.countDown();
        if (mode == CreateMode.LOSE_RESPONSE_AFTER_CREATE) {
            return;
        }
        if (mode == CreateMode.HOLD_RESPONSE_AFTER_CREATE && !awaitResponseRelease()) {
            return;
        }
        send(exchange, 202, JSON.writeValueAsString(transaction));
    }

    private JsonNode readBody(HttpExchange exchange) throws IOException {
        byte[] bytes = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
        if (bytes.length == 0 || bytes.length > MAX_BODY_BYTES) {
            return MissingNode.getInstance();
        }
        try {
            JsonNode parsed = JSON.readTree(bytes);
            return parsed == null ? MissingNode.getInstance() : parsed;
        } catch (IOException invalidJson) {
            return MissingNode.getInstance();
        }
    }

    private void capture(HttpExchange exchange, JsonNode body) {
        Map<String, String> types = new LinkedHashMap<>();
        body.fields().forEachRemaining(field -> types.put(field.getKey(), field.getValue().getNodeType().name()));
        String card = text(body, "cardNo");
        JsonNode amount = body.path("amount");
        requests.add(new CapturedRequest(exchange.getRequestMethod(), exchange.getRequestURI().getRawPath(),
            exchange.getRequestURI().getRawQuery(), exchange.getRequestHeaders().getFirst("X-USER-ID"),
            Map.copyOf(types), text(body, "orderId"),
            amount.isIntegralNumber() && amount.canConvertToLong() ? amount.longValue() : null,
            text(body, "cardType"), text(body, "callbackUrl"),
            card != null && MessageDigest.isEqual(expectedCardDigest, digest(card))));
    }

    private static boolean validCreate(JsonNode body) {
        String orderId = text(body, "orderId");
        JsonNode amount = body.path("amount");
        return body.isObject() && orderId != null && !orderId.isBlank() && orderId.length() >= 6
            && nonBlank(body, "cardType") && nonBlank(body, "cardNo") && nonBlank(body, "callbackUrl")
            && amount.isIntegralNumber() && amount.canConvertToLong() && amount.longValue() >= 0;
    }

    private static boolean nonBlank(JsonNode body, String field) {
        String value = text(body, field);
        return value != null && !value.isBlank();
    }

    private static String text(JsonNode body, String field) {
        JsonNode value = body.path(field);
        return value.isTextual() ? value.textValue() : null;
    }

    private static String orderQuery(URI uri) {
        if (uri.getRawQuery() != null) {
            for (String pair : uri.getRawQuery().split("&")) {
                String[] parts = pair.split("=", 2);
                if (parts.length == 2 && "orderId".equals(parts[0])) {
                    return URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
                }
            }
        }
        return null;
    }

    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Required test digest is unavailable", impossible);
        }
    }

    private static void send(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length != 0) {
            exchange.getResponseBody().write(bytes);
        }
    }

    private boolean awaitResponseRelease() {
        try {
            return responsesReleased.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void sendHeldBody(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes, 0, 1);
        exchange.getResponseBody().flush();
        bodyStarted.countDown();
        if (awaitResponseRelease()) {
            exchange.getResponseBody().write(bytes, 1, bytes.length - 1);
        }
    }

    @Override
    public void close() {
        releaseResponses();
        server.stop(0);
        workers.shutdownNow();
        try {
            if (!workers.awaitTermination(2, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Local PG workers did not terminate");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while closing local PG workers", interrupted);
        }
    }

    public enum CreateMode {
        NORMAL, REJECT_BEFORE_CREATE, LOSE_RESPONSE_AFTER_CREATE, HOLD_RESPONSE_AFTER_CREATE
    }

    public record CapturedRequest(String method, String rawPath, String rawQuery, String account,
                                  Map<String, String> fieldTypes, String orderId, Long amount, String cardType,
                                  String callbackUrl, boolean cardMatchesExpected) { }

    private record Transaction(String transactionKey, String orderId, long amount, String status) { }

    private enum Hold {
        NONE, BEFORE_HEADERS, AFTER_FIRST_BODY_BYTE
    }

    private record Reply(int status, String json, URI location, Hold hold) { }
}
