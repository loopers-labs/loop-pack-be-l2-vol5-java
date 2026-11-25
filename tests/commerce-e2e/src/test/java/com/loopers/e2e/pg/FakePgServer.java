package com.loopers.e2e.pg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loopers.application.order.PgOrderId;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * 거래를 저장하는 가짜 PG HTTP 서버 (W10 사고 실험). PgClient 가 쓰는 W5 계약을 그대로 받는다:
 * POST /api/v1/payments (X-USER-ID, 본문 {orderId "ORDER-{id}", cardType, cardNo, amount, callbackUrl}) · GET /api/v1/payments?orderId= (X-USER-ID).
 * 응답 본문은 {transactionKey, orderId, amount, status}.
 * <ul>
 *   <li>정상: PENDING 거래를 저장하고 200 PENDING. 최종 결과는 {@link #settle} 이 거래를 바꾸고 요청의 callbackUrl 로 실제 callback 을 보낸다.</li>
 *   <li>응답 유실 대상({@link #dropResponsesFor}): SUCCESS 거래를 저장한 뒤 200 + 빈 본문. callback 은 보내지 않는다 (PgClient 는 UNKNOWN).</li>
 *   <li>이미 거래가 있는 주문의 create: 중복 시도로 기록하고 409. 거래는 바꾸지 않는다.</li>
 *   <li>조회: 같은 사용자·같은 PG 주문 ID 의 거래만 200, 없거나 다른 사용자면 404.</li>
 * </ul>
 * create 시도·조회·callback 은 모두 기록하고 지우지 않는다. 응답 유실을 해제해도 거래와 기록은 남는다. 카드번호는 저장하지 않는다.
 */
public final class FakePgServer implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(FakePgServer.class);
    private static final String PATH = "/api/v1/payments";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public enum Status { PENDING, SUCCESS, FAILED }

    /** create 요청에 PG 가 한 일. */
    public enum CreateResult {
        /** PENDING 거래를 저장하고 200 PENDING 으로 응답. */
        ACCEPTED_PENDING,
        /** SUCCESS 거래를 저장하고 200 + 빈 본문으로 응답 (응답 유실). */
        SUCCEEDED_RESPONSE_DROPPED,
        /** 이미 거래가 있어 409. 거래는 그대로. */
        DUPLICATE_REJECTED,
        /** 본문 형식 오류로 400. */
        BAD_REQUEST
    }

    public record Transaction(String transactionKey, String userId, String pgOrderId, long amount, Status status,
                              String callbackUrl, Instant createdAt, Instant updatedAt) {
        Transaction withStatus(Status next, Instant at) {
            return new Transaction(transactionKey, userId, pgOrderId, amount, next, callbackUrl, createdAt, at);
        }
    }

    public record CreateAttempt(String userId, String pgOrderId, Long amount, CreateResult result, String transactionKey, Instant at) {
    }

    public record Lookup(String userId, String pgOrderId, int responseStatus, Status returnedStatus, Instant at) {
    }

    /** PG 가 보낸 callback 과 commerce 의 HTTP 응답 코드 (-1: 전송 실패). */
    public record Callback(String pgOrderId, String transactionKey, Status status, int responseStatus, Instant at) {
    }

    private final HttpServer server;
    private final ExecutorService executor;
    private final HttpClient callbackClient;

    private final Object lock = new Object();
    private final Map<String, Transaction> transactions = new LinkedHashMap<>();
    private final List<CreateAttempt> createAttempts = new ArrayList<>();
    private final List<Lookup> lookups = new ArrayList<>();
    private final List<Callback> callbacks = new ArrayList<>();
    private volatile Set<String> dropTargets = Set.of();

    private FakePgServer(HttpServer server, ExecutorService executor) {
        this.server = server;
        this.executor = executor;
        this.callbackClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(1))
            .build();
    }

    /** 사용 가능한 임의 포트에 띄운다. 주소는 {@link #baseUrl()}. */
    public static FakePgServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            ExecutorService executor = Executors.newCachedThreadPool();
            server.setExecutor(executor);
            FakePgServer pg = new FakePgServer(server, executor);
            server.createContext(PATH, pg::handle);
            server.start();
            return pg;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    // ---- 제어 ----

    /** 이 주문들의 다음 create 부터 SUCCESS 거래를 저장한 뒤 응답 본문을 버린다. 이전 대상은 바뀐다. */
    public void dropResponsesFor(Collection<Long> orderIds) {
        dropTargets = orderIds.stream().map(PgOrderId::of).collect(Collectors.toUnmodifiableSet());
        log.info("[fake-pg] 응답 유실 ON [targets = {}]", dropTargets);
    }

    /** 응답 유실을 끈다. 저장한 거래·기록은 그대로 둔다. */
    public void stopDropping() {
        dropTargets = Set.of();
        log.info("[fake-pg] 응답 유실 OFF");
    }

    public boolean isDropping() {
        return !dropTargets.isEmpty();
    }

    public Set<String> dropTargets() {
        return dropTargets;
    }

    /** PENDING 거래를 최종 결과로 바꾸고 callback 을 보낸다. 돌려주는 값은 commerce 의 HTTP 응답 코드. */
    public int settle(Long orderId, Status result) {
        if (result == Status.PENDING) {
            throw new IllegalArgumentException("최종 결과는 SUCCESS·FAILED 다");
        }
        String pgOrderId = PgOrderId.of(orderId);
        synchronized (lock) {
            Transaction transaction = transactions.get(pgOrderId);
            if (transaction == null || transaction.status() != Status.PENDING) {
                throw new IllegalStateException("PENDING 거래가 없다 [pgOrderId = " + pgOrderId + ", transaction = " + transaction + "]");
            }
            transactions.put(pgOrderId, transaction.withStatus(result, Instant.now()));
        }
        return sendCallback(orderId);
    }

    /** 현재 거래 상태로 callback 을 (다시) 보낸다. 돌려주는 값은 commerce 의 HTTP 응답 코드, 전송 실패면 -1. */
    public int sendCallback(Long orderId) {
        Transaction transaction = transaction(orderId)
            .orElseThrow(() -> new IllegalStateException("거래가 없다 [pgOrderId = " + PgOrderId.of(orderId) + "]"));
        int responseStatus;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(transaction.callbackUrl()))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body(transaction)))
                .build();
            responseStatus = callbackClient.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (IOException e) {
            responseStatus = -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            responseStatus = -1;
        }
        synchronized (lock) {
            callbacks.add(new Callback(transaction.pgOrderId(), transaction.transactionKey(), transaction.status(), responseStatus, Instant.now()));
        }
        log.info("[fake-pg] callback [pgOrderId = {}, transactionKey = {}, status = {}, response = {}]",
            transaction.pgOrderId(), transaction.transactionKey(), transaction.status(), responseStatus);
        return responseStatus;
    }

    // ---- 관찰 (모두 복사본) ----

    /** 저장한 거래, 처음 저장한 순서대로. */
    public List<Transaction> transactions() {
        synchronized (lock) {
            return List.copyOf(transactions.values());
        }
    }

    public Optional<Transaction> transaction(Long orderId) {
        synchronized (lock) {
            return Optional.ofNullable(transactions.get(PgOrderId.of(orderId)));
        }
    }

    /** 받은 create 요청 전부 (거절한 것 포함), 받은 순서대로. */
    public List<CreateAttempt> createAttempts() {
        synchronized (lock) {
            return List.copyOf(createAttempts);
        }
    }

    public long createCount(Long orderId) {
        String pgOrderId = PgOrderId.of(orderId);
        return createAttempts().stream().filter(attempt -> pgOrderId.equals(attempt.pgOrderId())).count();
    }

    public List<CreateAttempt> duplicateCreates() {
        return createAttempts().stream().filter(attempt -> attempt.result() == CreateResult.DUPLICATE_REJECTED).toList();
    }

    public List<Lookup> lookups() {
        synchronized (lock) {
            return List.copyOf(lookups);
        }
    }

    public List<Callback> callbacks() {
        synchronized (lock) {
            return List.copyOf(callbacks);
        }
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
        callbackClient.close();
    }

    // ---- HTTP ----

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!PATH.equals(exchange.getRequestURI().getPath())) {
                write(exchange, 404, null);
            } else if ("POST".equals(exchange.getRequestMethod())) {
                create(exchange);
            } else if ("GET".equals(exchange.getRequestMethod())) {
                find(exchange);
            } else {
                write(exchange, 405, null);
            }
        }
    }

    private void create(HttpExchange exchange) throws IOException {
        String userId = exchange.getRequestHeaders().getFirst("X-USER-ID");
        JsonNode body = readBody(exchange);
        String pgOrderId = text(body, "orderId");
        Long amount = body != null && body.path("amount").canConvertToLong() ? body.path("amount").asLong() : null;
        String callbackUrl = text(body, "callbackUrl");
        Instant now = Instant.now();

        if (userId == null || pgOrderId == null || amount == null || amount <= 0 || callbackUrl == null) {
            record(new CreateAttempt(userId, pgOrderId, amount, CreateResult.BAD_REQUEST, null, now));
            write(exchange, 400, null);
            return;
        }

        boolean drop = dropTargets.contains(pgOrderId);
        Transaction transaction;
        CreateResult result;
        synchronized (lock) {
            Transaction existing = transactions.get(pgOrderId);
            if (existing != null) {
                createAttempts.add(new CreateAttempt(userId, pgOrderId, amount, CreateResult.DUPLICATE_REJECTED, existing.transactionKey(), now));
                transaction = existing;
                result = CreateResult.DUPLICATE_REJECTED;
            } else {
                transaction = new Transaction("pg-tx-" + UUID.randomUUID(), userId, pgOrderId, amount,
                    drop ? Status.SUCCESS : Status.PENDING, callbackUrl, now, now);
                transactions.put(pgOrderId, transaction);
                result = drop ? CreateResult.SUCCEEDED_RESPONSE_DROPPED : CreateResult.ACCEPTED_PENDING;
                createAttempts.add(new CreateAttempt(userId, pgOrderId, amount, result, transaction.transactionKey(), now));
            }
        }
        log.info("[fake-pg] create [userId = {}, pgOrderId = {}, amount = {}, result = {}, transactionKey = {}]",
            userId, pgOrderId, amount, result, transaction.transactionKey());

        switch (result) {
            case DUPLICATE_REJECTED -> write(exchange, 409, null);
            case SUCCEEDED_RESPONSE_DROPPED -> write(exchange, 200, null);
            default -> write(exchange, 200, body(transaction));
        }
    }

    private void find(HttpExchange exchange) throws IOException {
        String userId = exchange.getRequestHeaders().getFirst("X-USER-ID");
        String pgOrderId = queryParam(exchange.getRequestURI().getQuery(), "orderId");
        Transaction transaction;
        synchronized (lock) {
            Transaction stored = pgOrderId == null ? null : transactions.get(pgOrderId);
            transaction = stored != null && stored.userId().equals(userId) ? stored : null;
            lookups.add(new Lookup(userId, pgOrderId, transaction == null ? 404 : 200,
                transaction == null ? null : transaction.status(), Instant.now()));
        }
        log.info("[fake-pg] lookup [userId = {}, pgOrderId = {}, status = {}]",
            userId, pgOrderId, transaction == null ? "NOT_FOUND" : transaction.status());
        if (transaction == null) {
            write(exchange, 404, null);
        } else {
            write(exchange, 200, body(transaction));
        }
    }

    private void record(CreateAttempt attempt) {
        synchronized (lock) {
            createAttempts.add(attempt);
        }
    }

    private static String body(Transaction transaction) {
        ObjectNode node = OBJECT_MAPPER.createObjectNode();
        node.put("transactionKey", transaction.transactionKey());
        node.put("orderId", transaction.pgOrderId());
        node.put("amount", transaction.amount());
        node.put("status", transaction.status().name());
        return node.toString();
    }

    private static JsonNode readBody(HttpExchange exchange) throws IOException {
        byte[] bytes = exchange.getRequestBody().readAllBytes();
        try {
            return OBJECT_MAPPER.readTree(bytes);
        } catch (IOException e) {
            return null;
        }
    }

    private static String text(JsonNode body, String field) {
        if (body == null || !body.path(field).isTextual() || body.path(field).asText().isBlank()) {
            return null;
        }
        return body.path(field).asText();
    }

    private static String queryParam(String query, String name) {
        if (query == null) {
            return null;
        }
        for (String pair : query.split("&")) {
            int separator = pair.indexOf('=');
            if (separator > 0 && pair.substring(0, separator).equals(name)) {
                return URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    /** body 가 null 이면 본문 없이 (Content-Length 0) 보낸다. */
    private static void write(HttpExchange exchange, int status, String body) throws IOException {
        if (body == null) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
