package com.loopers.infrastructure.payment;

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

/**
 * 계약 테스트용 로컬 PG 서버 (설계 1-6 결정 ㉖). JDK 내장 HttpServer 를 포트 0 으로 띄워 진짜 소켓을 거친다.
 * 테스트가 경로별 응답을 미리 정하고, 서버는 받은 요청(메서드·경로·쿼리·헤더·본문)과 횟수를 기록한다.
 * 지연은 무작위가 아니라 래치로만 만든다. holdResponses() 뒤의 요청은 releaseResponses() 까지 응답을 보내지 않는다.
 * 카드 번호가 본문에 들어오므로 이 클래스는 아무것도 로그에 남기지 않는다.
 */
public class TestPaymentPgServer implements AutoCloseable {

    private static final String PAYMENTS_PATH = "/api/v1/payments";
    private static final long HOLD_TIMEOUT_SECONDS = 30;

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

    private final HttpServer server;
    private final List<ReceivedRequest> received = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Response> transactionResponses = new ConcurrentHashMap<>();
    private final Map<String, Response> listResponses = new ConcurrentHashMap<>();
    private volatile Response createResponse = new Response(202, null);
    private volatile CountDownLatch hold = new CountDownLatch(0);

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

    public List<ReceivedRequest> receivedRequests() {
        return List.copyOf(received);
    }

    public long createCount() {
        return received.stream().filter(request -> "POST".equals(request.method()) && PAYMENTS_PATH.equals(request.path())).count();
    }

    public void reset() {
        releaseResponses();
        received.clear();
        transactionResponses.clear();
        listResponses.clear();
        createResponse = new Response(202, null);
    }

    @Override
    public void close() {
        releaseResponses();
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
        respond(exchange, route(request));
    }

    private Response route(ReceivedRequest request) {
        if ("POST".equals(request.method()) && PAYMENTS_PATH.equals(request.path())) {
            return createResponse;
        }
        if ("GET".equals(request.method()) && PAYMENTS_PATH.equals(request.path())) {
            return listResponses.getOrDefault(request.query().get("orderId"), new Response(200, "[]"));
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