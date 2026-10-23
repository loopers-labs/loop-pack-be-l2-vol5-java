package com.loopers.support.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class LocalPgServer implements AutoCloseable {
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    private final List<Transaction> transactions = new CopyOnWriteArrayList<>();
    private final List<String> lookupRequests = new CopyOnWriteArrayList<>();
    private final Queue<Integer> lookupResponseSequence = new ConcurrentLinkedQueue<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final AtomicLong transactionSequence = new AtomicLong();
    private final HttpServer server;
    private volatile String receivedAccount;
    private volatile String lastLookupTransactionKey;
    private volatile int rejectionStatus = 422;
    private volatile String rejectionBodyOverride;
    private volatile String createResponseBodyOverride;
    private volatile CreateResponse createResponse = CreateResponse.NORMAL;
    private volatile int lookupResponseStatus = 200;
    private volatile String lookupResponseBodyOverride;
    private volatile boolean truncateLookupBody;
    private volatile CountDownLatch createResponseGate = new CountDownLatch(0);
    private volatile CountDownLatch lookupResponseGate = new CountDownLatch(0);
    private volatile CountDownLatch lookupBodyGate = new CountDownLatch(0);
    private volatile CountDownLatch lookupStarted = new CountDownLatch(1);
    private volatile CountDownLatch createStarted = new CountDownLatch(1);

    private LocalPgServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/api/v1/payments", this::handlePayment);
        server.start();
    }

    public static LocalPgServer start() {
        try {
            return new LocalPgServer();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void reset() {
        releaseResponses();
        requests.clear();
        transactions.clear();
        lookupRequests.clear();
        lookupResponseSequence.clear();
        receivedAccount = null;
        lastLookupTransactionKey = null;
        rejectionStatus = 422;
        rejectionBodyOverride = null;
        createResponseBodyOverride = null;
        createResponse = CreateResponse.NORMAL;
        lookupResponseStatus = 200;
        lookupResponseBodyOverride = null;
        truncateLookupBody = false;
        createResponseGate = new CountDownLatch(0);
        lookupResponseGate = new CountDownLatch(0);
        lookupBodyGate = new CountDownLatch(0);
        lookupStarted = new CountDownLatch(1);
        createStarted = new CountDownLatch(1);
    }

    public void respondWithDifferentOrderId() {
        createResponse = CreateResponse.DIFFERENT_ORDER_ID;
    }

    public void respondWithDifferentAmount() {
        createResponse = CreateResponse.DIFFERENT_AMOUNT;
    }

    public void loseResponseAfterCreatingTransaction() {
        createResponse = CreateResponse.LOST;
    }

    public void rejectBeforeCreatingTransaction() {
        createResponse = CreateResponse.REJECTED_BEFORE_CREATE;
    }

    public void respondBeforeCreateWith(int status, String body) {
        rejectBeforeCreatingTransaction();
        rejectionStatus = status;
        rejectionBodyOverride = body;
    }

    public String lastLookupTransactionKey() {
        return lastLookupTransactionKey;
    }

    public int createRequestCount() {
        return requests.size();
    }

    public JsonNode receivedRequest() {
        return requests.getFirst();
    }

    public String receivedAccount() {
        return receivedAccount;
    }

    public List<Transaction> transactions() {
        return List.copyOf(transactions);
    }

    public void succeedPaymentForOrder(String providerOrderId) {
        transactions.replaceAll(transaction -> transaction.orderId().equals(providerOrderId)
                ? new Transaction(transaction.transactionKey(), transaction.orderId(), transaction.amount(), TransactionStatus.SUCCESS)
                : transaction);
    }

    public void failPaymentForOrder(String providerOrderId) {
        transactions.replaceAll(transaction -> transaction.orderId().equals(providerOrderId)
                ? new Transaction(transaction.transactionKey(), transaction.orderId(), transaction.amount(), TransactionStatus.FAILED)
                : transaction);
    }

    public void failPaymentLookups() {
        lookupResponseStatus = 503;
    }

    public void failNextPaymentLookup() {
        lookupResponseSequence.add(503);
    }

    public void respondToLookupRequestsWith(int... statuses) {
        for (int status : statuses) {
            lookupResponseSequence.add(status);
        }
    }

    public void respondWithMalformedLookupResponse() {
        respondWithLookupBody("not-json");
    }

    public void respondWithLookupBody(String body) {
        lookupResponseBodyOverride = body;
    }

    public void respondWithCreateBody(String body) {
        createResponseBodyOverride = body;
    }

    public void rejectPaymentLookups() {
        lookupResponseStatus = 400;
    }

    public void restorePaymentLookups() {
        lookupResponseStatus = 200;
    }

    public void withholdCreateResponse() {
        createResponseGate = new CountDownLatch(1);
        createStarted = new CountDownLatch(1);
    }

    public boolean awaitCreateRequest() throws InterruptedException {
        return createStarted.await(2, TimeUnit.SECONDS);
    }

    public void withholdLookupResponse() {
        lookupResponseGate = new CountDownLatch(1);
        lookupStarted = new CountDownLatch(1);
    }

    public boolean awaitLookupRequest() throws InterruptedException {
        return lookupStarted.await(2, TimeUnit.SECONDS);
    }

    public void withholdLookupResponseBody() {
        lookupBodyGate = new CountDownLatch(1);
    }

    public void truncateLookupResponseBody() {
        truncateLookupBody = true;
    }

    public void releaseResponses() {
        createResponseGate.countDown();
        lookupResponseGate.countDown();
        lookupBodyGate.countDown();
    }

    public int lookupRequestCount() {
        return lookupRequests.size();
    }

    public String lastLookupOrderId() {
        return lookupRequests.getLast();
    }

    private void handlePayment(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if ("GET".equals(exchange.getRequestMethod())
                && ("/api/v1/payments".equals(path) || path.startsWith("/api/v1/payments/"))) {
            findPayments(exchange);
            return;
        }
        createPayment(exchange);
    }

    private void findPayments(HttpExchange exchange) throws IOException {
        try (exchange) {
            CountDownLatch responseGate = lookupResponseGate;
            CountDownLatch bodyGate = lookupBodyGate;
            receivedAccount = exchange.getRequestHeaders().getFirst("X-USER-ID");
            String query = exchange.getRequestURI().getRawQuery();
            String providerOrderId = query == null ? "" : Arrays.stream(query.split("&"))
                    .filter(parameter -> parameter.startsWith("orderId="))
                    .map(parameter -> URLDecoder.decode(parameter.substring("orderId=".length()), StandardCharsets.UTF_8))
                    .findFirst().orElse("");
            String path = exchange.getRequestURI().getPath();
            boolean byTransactionKey = path.startsWith("/api/v1/payments/");
            String transactionKey = byTransactionKey ? path.substring("/api/v1/payments/".length()) : null;
            lastLookupTransactionKey = transactionKey;
            lookupRequests.add(byTransactionKey ? transactionKey : providerOrderId);
            lookupStarted.countDown();
            Integer nextStatus = lookupResponseSequence.poll();
            int responseStatus = nextStatus == null ? lookupResponseStatus : nextStatus;
            if (responseStatus != 200) {
                exchange.sendResponseHeaders(responseStatus, -1);
                return;
            }
            var matches = transactions.stream().filter(payment -> byTransactionKey
                    ? payment.transactionKey().equals(transactionKey) : payment.orderId().equals(providerOrderId)).toList();
            if (byTransactionKey && matches.isEmpty()) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            String bodyOverride = lookupResponseBodyOverride;
            Object responseBody = byTransactionKey ? matches.getFirst() : matches;
            byte[] body = bodyOverride == null ? mapper.writeValueAsBytes(responseBody)
                    : bodyOverride.getBytes(StandardCharsets.UTF_8);
            awaitResponseRelease(responseGate);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            if (truncateLookupBody || bodyGate.getCount() > 0) {
                exchange.getResponseBody().write(body, 0, 1);
                exchange.getResponseBody().flush();
                if (truncateLookupBody) {
                    return;
                }
                awaitResponseRelease(bodyGate);
                exchange.getResponseBody().write(body, 1, body.length - 1);
                return;
            }
            exchange.getResponseBody().write(body);
        }
    }

    private void createPayment(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!"POST".equals(exchange.getRequestMethod())
                    || !"/api/v1/payments".equals(exchange.getRequestURI().getPath())) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            CountDownLatch responseGate = createResponseGate;
            receivedAccount = exchange.getRequestHeaders().getFirst("X-USER-ID");
            JsonNode request = mapper.readTree(exchange.getRequestBody());
            requests.add(request);
            if (createResponse == CreateResponse.REJECTED_BEFORE_CREATE) {
                createStarted.countDown();
                sendRejection(exchange, request);
                return;
            }
            Transaction transaction = new Transaction(
                    "TEST-TX-" + transactionSequence.incrementAndGet(),
                    request.path("orderId").asText(), request.path("amount").asLong(), TransactionStatus.PENDING);
            // 같은 주문번호라도 매 POST마다 새 거래다. PG가 중복 결제를 막아주지 않는다.
            transactions.add(transaction);
            createStarted.countDown();
            ObjectNode responseBody = mapper.valueToTree(transaction);
            CreateResponse responseMode = createResponse;
            if (responseMode == CreateResponse.DIFFERENT_ORDER_ID) {
                responseBody.put("orderId", transaction.orderId() + "-OTHER");
            } else if (responseMode == CreateResponse.DIFFERENT_AMOUNT) {
                responseBody.put("amount", transaction.amount() + 1);
            }
            byte[] body = createResponseBodyOverride == null ? mapper.writeValueAsBytes(responseBody)
                    : createResponseBodyOverride.getBytes(StandardCharsets.UTF_8);
            awaitResponseRelease(responseGate);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(202, body.length);
            if (responseMode == CreateResponse.LOST) {
                // 거래는 남기고 응답 본문을 보내지 않은 채 연결을 닫는다.
                return;
            }
            exchange.getResponseBody().write(body);
        }
    }

    private void sendRejection(HttpExchange exchange, JsonNode request) throws IOException {
        ObjectNode rejection = mapper.createObjectNode();
        rejection.put("code", "TEST_PG_REJECTED_BEFORE_CREATE");
        rejection.put("orderId", request.path("orderId").asText());
        rejection.put("amount", request.path("amount").asLong());
        rejection.put("transactionCreated", false);
        byte[] body = rejectionBodyOverride == null ? mapper.writeValueAsBytes(rejection)
                : rejectionBodyOverride.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(rejectionStatus, body.length);
        exchange.getResponseBody().write(body);
    }

    @Override
    public void close() {
        releaseResponses();
        server.stop(0);
        executor.shutdownNow();
    }

    private static void awaitResponseRelease(CountDownLatch responseGate) throws IOException {
        try {
            if (!responseGate.await(10, TimeUnit.SECONDS)) {
                throw new IOException("테스트 PG 응답 보류 시간이 초과됐습니다.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("테스트 PG 응답 대기가 중단됐습니다.", exception);
        }
    }

    public enum TransactionStatus {
        PENDING,
        SUCCESS,
        FAILED
    }

    public record Transaction(String transactionKey, String orderId, long amount, TransactionStatus status) {}

    private enum CreateResponse {
        NORMAL,
        DIFFERENT_ORDER_ID,
        DIFFERENT_AMOUNT,
        LOST,
        REJECTED_BEFORE_CREATE
    }
}
