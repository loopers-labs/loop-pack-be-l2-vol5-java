package com.loopers.domains.payment.out;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loopers.domains.payment.out.TestPgServer.CreateBehavior;
import com.loopers.domains.payment.out.TestPgServer.LookupBehavior;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

// 사람 흐름 리허설에서 띄운 TestPgServer의 장애 동작을 HTTP로 바꾸는 제어 서버. 결제 계약과 섞지 않으려고 포트를 따로 쓴다.
// 인증이 없으므로 127.0.0.1에만 묶는다.
public final class TestPgControlServer implements AutoCloseable {
    static final String CONTROL_PATH = "/control";
    private static final String STATUS_PATH = CONTROL_PATH + "/status";
    private static final String CREATE_BEHAVIOR_PATH = CONTROL_PATH + "/create-behavior";
    private static final String LOOKUP_BEHAVIOR_PATH = CONTROL_PATH + "/lookup-behavior";
    private static final String LOST_RESPONSES_PREFIX = CONTROL_PATH + "/lost-responses/";
    private static final String LOST_CANCELS_PREFIX = CONTROL_PATH + "/lost-cancels/";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TestPgServer testPgServer;
    private final HttpServer server;

    public TestPgControlServer(TestPgServer testPgServer, int port) throws IOException {
        this.testPgServer = testPgServer;
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        this.server.createContext(CONTROL_PATH, this::handle);
        this.server.start();
    }

    public URI baseUrl() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    InetSocketAddress address() {
        return server.getAddress();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getRawPath();
            if (method.equals("GET") && path.equals(STATUS_PATH)) {
                send(exchange, 200, statusJson());
            } else if (method.equals("PUT") && path.equals(CREATE_BEHAVIOR_PATH)) {
                changeCreateBehavior(exchange);
            } else if (method.equals("PUT") && path.equals(LOOKUP_BEHAVIOR_PATH)) {
                changeLookupBehavior(exchange);
            } else if (path.startsWith(LOST_RESPONSES_PREFIX) && path.length() > LOST_RESPONSES_PREFIX.length()) {
                changeLostResponse(exchange, method, path);
            } else if (path.startsWith(LOST_CANCELS_PREFIX) && path.length() > LOST_CANCELS_PREFIX.length()) {
                changeLostCancel(exchange, method, path);
            } else {
                send(exchange, 404, null);
            }
        }
    }

    private void changeCreateBehavior(HttpExchange exchange) throws IOException {
        CreateBehavior behavior;
        try {
            JsonNode body = objectMapper.readTree(exchange.getRequestBody().readAllBytes());
            behavior = CreateBehavior.valueOf(body.path("behavior").asText());
        } catch (IOException | IllegalArgumentException e) {
            // 본문이 깨졌거나 없는 동작 이름이면 기존 동작을 그대로 두고 거절한다.
            send(exchange, 400, "{\"code\":\"UNKNOWN_CREATE_BEHAVIOR\"}");
            return;
        }
        testPgServer.createBehavior(behavior);
        send(exchange, 204, null);
    }

    private void changeLookupBehavior(HttpExchange exchange) throws IOException {
        LookupBehavior behavior;
        try {
            JsonNode body = objectMapper.readTree(exchange.getRequestBody().readAllBytes());
            behavior = LookupBehavior.valueOf(body.path("behavior").asText());
        } catch (IOException | IllegalArgumentException e) {
            // 본문이 깨졌거나 없는 동작 이름이면 기존 동작을 그대로 두고 거절한다.
            send(exchange, 400, "{\"code\":\"UNKNOWN_LOOKUP_BEHAVIOR\"}");
            return;
        }
        testPgServer.lookupBehavior(behavior);
        send(exchange, 204, null);
    }

    private void changeLostResponse(HttpExchange exchange, String method, String path) throws IOException {
        String providerOrderId = URLDecoder.decode(path.substring(LOST_RESPONSES_PREFIX.length()), StandardCharsets.UTF_8);
        if (method.equals("PUT")) {
            testPgServer.loseResponseAfterSuccess(providerOrderId);
            send(exchange, 204, null);
        } else if (method.equals("DELETE")) {
            testPgServer.clearOrderBehavior(providerOrderId);
            send(exchange, 204, null);
        } else {
            send(exchange, 405, null);
        }
    }

    private void changeLostCancel(HttpExchange exchange, String method, String path) throws IOException {
        String providerOrderId = URLDecoder.decode(path.substring(LOST_CANCELS_PREFIX.length()), StandardCharsets.UTF_8);
        if (method.equals("PUT")) {
            testPgServer.loseCancelResponse(providerOrderId);
            send(exchange, 204, null);
        } else if (method.equals("DELETE")) {
            testPgServer.clearLostCancel(providerOrderId);
            send(exchange, 204, null);
        } else {
            send(exchange, 405, null);
        }
    }

    private String statusJson() {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("createBehavior", testPgServer.createBehavior().name());
        node.put("lookupBehavior", testPgServer.lookupBehavior().name());
        node.put("createCalls", testPgServer.createCalls());
        node.put("lookupCalls", testPgServer.lookupCalls());
        node.put("cancelCalls", testPgServer.cancelCalls());
        ArrayNode lost = node.putArray("lostResponseOrderIds");
        testPgServer.lostResponseOrderIds().stream().sorted().forEach(lost::add);
        ArrayNode lostCancels = node.putArray("lostCancelOrderIds");
        testPgServer.lostCancelOrderIds().stream().sorted().forEach(lostCancels::add);
        return node.toString();
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
