package com.loopers.support.pg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class PgFixture implements AutoCloseable {

    private static final char[] STORE_PASSWORD = "local-only-not-a-secret".toCharArray();
    private static final String PAYMENTS = "/api/v1/payments";
    private static final String USER_HEADER = "X-USER-ID";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient CALLBACK_CLIENT = HttpClient.newHttpClient();

    private final HttpsServer server;
    private final ExecutorService executor;
    private final AtomicInteger createCount = new AtomicInteger();
    private final AtomicInteger transactionSequence = new AtomicInteger();
    private final Map<String, Stored> transactions = new ConcurrentHashMap<>();
    private final List<ReceivedRequest> received = new CopyOnWriteArrayList<>();
    private final AtomicBoolean rejectNext = new AtomicBoolean();
    private final AtomicReference<RawResponse> nextCreateResponse = new AtomicReference<>();
    private final AtomicReference<RawResponse> nextLookupResponse = new AtomicReference<>();
    private final AtomicBoolean dropNextResponse = new AtomicBoolean();
    private final AtomicReference<HeldResponse> holdNext = new AtomicReference<>();

    private PgFixture(HttpsServer server, ExecutorService executor) {
        this.server = server;
        this.executor = executor;
    }

    public static PgFixture start() {
        try {
            HttpsServer server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setHttpsConfigurator(new HttpsConfigurator(serverSslContext()));
            ExecutorService executor = Executors.newCachedThreadPool();
            server.setExecutor(executor);
            PgFixture fixture = new PgFixture(server, executor);
            server.createContext(PAYMENTS, fixture::handle);
            server.start();
            return fixture;
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public String baseUrl() {
        return "https://127.0.0.1:" + server.getAddress().getPort();
    }

    public List<ReceivedRequest> receivedRequests() {
        return List.copyOf(received);
    }

    public int createCount() {
        return createCount.get();
    }

    public List<Transaction> transactionsOf(String orderId) {
        return transactions.values().stream()
            .map(Stored::transaction)
            .filter(transaction -> transaction.orderId().equals(orderId))
            .toList();
    }

    public void changeStatus(String transactionKey, Status status) {
        Stored changed = transactions.computeIfPresent(transactionKey, (key, stored) -> stored.withStatus(status));
        if (changed == null) {
            throw new IllegalArgumentException("unknown transaction: " + transactionKey);
        }
    }

    public int sendCallback(String transactionKey) {
        Stored stored = transactions.get(transactionKey);
        if (stored == null) {
            throw new IllegalArgumentException("unknown transaction: " + transactionKey);
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(stored.callbackUrl()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(stored.transaction())))
                .build();
            return CALLBACK_CLIENT.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    public void rejectNextCreate() {
        rejectNext.set(true);
    }

    public void respondNextCreateWith(int status, String rawBody) {
        nextCreateResponse.set(new RawResponse(status, rawBody));
    }

    public void respondNextLookupWith(int status, String rawBody) {
        nextLookupResponse.set(new RawResponse(status, rawBody));
    }

    public static SSLContext clientSslContext() {
        try {
            TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trust.init(load("pg-fixture-truststore.p12"));
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trust.getTrustManagers(), null);
            return context;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public void dropNextCreateResponse() {
        dropNextResponse.set(true);
    }

    public HeldResponse holdNextCreateResponse() {
        HeldResponse held = new HeldResponse();
        holdNext.set(held);
        return held;
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if ("POST".equals(exchange.getRequestMethod())) {
                create(exchange);
                return;
            }
            if ("GET".equals(exchange.getRequestMethod())) {
                lookup(exchange);
                return;
            }
            exchange.sendResponseHeaders(404, -1);
        }
    }

    private void create(HttpExchange exchange) throws IOException {
        JsonNode request = JSON.readTree(exchange.getRequestBody());
        received.add(new ReceivedRequest(
            exchange.getRequestMethod(),
            exchange.getRequestURI().getPath(),
            exchange.getRequestURI().getRawQuery(),
            userOf(exchange),
            exchange.getRequestHeaders().getFirst("Content-Type"),
            request
        ));
        createCount.incrementAndGet();
        RawResponse raw = nextCreateResponse.getAndSet(null);
        if (raw != null) {
            respondRaw(exchange, raw.status(), raw.body());
            return;
        }
        if (rejectNext.compareAndSet(true, false)) {
            respond(exchange, 422, new Rejection(
                "TEST_PG_REJECTED_BEFORE_CREATE",
                false,
                request.get("orderId").asText(),
                request.get("amount").asLong()
            ));
            return;
        }
        Transaction transaction = new Transaction(
            "TEST-TX-%04d".formatted(transactionSequence.incrementAndGet()),
            request.get("orderId").asText(),
            request.get("amount").asLong(),
            Status.PENDING
        );
        transactions.put(transaction.transactionKey(),
            new Stored(userOf(exchange), request.get("callbackUrl").asText(), transaction));
        HeldResponse held = holdNext.getAndSet(null);
        if (held != null) {
            held.markStored();
            held.awaitRelease();
        }
        if (dropNextResponse.compareAndSet(true, false)) {
            return;
        }
        respond(exchange, 202, transaction);
    }

    private void lookup(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        received.add(new ReceivedRequest(
            exchange.getRequestMethod(),
            path,
            exchange.getRequestURI().getRawQuery(),
            userOf(exchange),
            exchange.getRequestHeaders().getFirst("Content-Type"),
            null
        ));
        RawResponse raw = nextLookupResponse.getAndSet(null);
        if (raw != null) {
            respondRaw(exchange, raw.status(), raw.body());
            return;
        }
        if (path.equals(PAYMENTS)) {
            lookupByOrder(exchange);
            return;
        }
        if (!path.startsWith(PAYMENTS + "/")) {
            exchange.sendResponseHeaders(404, -1);
            return;
        }
        Stored stored = transactions.get(path.substring(PAYMENTS.length() + 1));
        if (stored == null || !stored.ownedBy(userOf(exchange))) {
            exchange.sendResponseHeaders(404, -1);
            return;
        }
        respond(exchange, 200, stored.transaction());
    }

    private void lookupByOrder(HttpExchange exchange) throws IOException {
        String orderId = queryParameter(exchange, "orderId");
        String userId = userOf(exchange);
        List<Transaction> found = transactions.values().stream()
            .filter(stored -> stored.ownedBy(userId))
            .map(Stored::transaction)
            .filter(transaction -> transaction.orderId().equals(orderId))
            .toList();
        respond(exchange, 200, found);
    }

    private static String userOf(HttpExchange exchange) {
        return exchange.getRequestHeaders().getFirst(USER_HEADER);
    }

    private static String queryParameter(HttpExchange exchange, String name) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null) {
            return null;
        }
        return Arrays.stream(query.split("&"))
            .filter(pair -> pair.startsWith(name + "="))
            .map(pair -> URLDecoder.decode(pair.substring(name.length() + 1), StandardCharsets.UTF_8))
            .findFirst()
            .orElse(null);
    }

    private static void respondRaw(HttpExchange exchange, int status, String body) throws IOException {
        if (body.isEmpty()) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static void respond(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static SSLContext serverSslContext() throws GeneralSecurityException {
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(load("pg-fixture-keystore.p12"), STORE_PASSWORD);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keys.getKeyManagers(), null, null);
        return context;
    }

    private static KeyStore load(String resource) {
        try (InputStream in = PgFixture.class.getResourceAsStream("/" + resource)) {
            KeyStore store = KeyStore.getInstance("PKCS12");
            store.load(in, STORE_PASSWORD);
            return store;
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    public enum Status {
        PENDING, SUCCESS, FAILED
    }

    public static final class HeldResponse {

        private final CountDownLatch stored = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);

        public void awaitStored() {
            await(stored);
        }

        public void release() {
            released.countDown();
        }

        private void markStored() {
            stored.countDown();
        }

        private void awaitRelease() {
            await(released);
        }

        private static void await(CountDownLatch latch) {
            try {
                if (!latch.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("held response timed out");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }

    private record RawResponse(int status, String body) {
    }

    public record ReceivedRequest(
        String method,
        String path,
        String rawQuery,
        String userId,
        String contentType,
        JsonNode body
    ) {
    }

    public record Transaction(String transactionKey, String orderId, long amount, Status status) {

        Transaction withStatus(Status changed) {
            return new Transaction(transactionKey, orderId, amount, changed);
        }
    }

    private record Stored(String userId, String callbackUrl, Transaction transaction) {

        boolean ownedBy(String requester) {
            return userId != null && userId.equals(requester);
        }

        Stored withStatus(Status changed) {
            return new Stored(userId, callbackUrl, transaction.withStatus(changed));
        }
    }

    private record Rejection(String code, boolean transactionCreated, String orderId, long amount) {
    }
}
