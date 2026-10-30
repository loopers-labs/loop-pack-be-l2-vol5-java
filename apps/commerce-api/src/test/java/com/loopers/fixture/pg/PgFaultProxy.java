package com.loopers.fixture.pg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * commerce 와 PG 사이의 장애 주입 프록시(5.7). 계약 경로의 요청을 받으면 수신을 기록하고 PG 에 그대로 전달해 응답 전체를 받은 뒤,
 * 그 대상의 reply plan 을 적용해 commerce 에 응답한다. PG 가 거래를 저장한 뒤 응답하므로 "거래 효과 뒤 응답 지연·손상" 순서가
 * 그대로 성립한다. PG 응답이 400(헤더·입력 검증 실패)이면 plan 을 소비하지 않는다.
 */
final class PgFaultProxy {

    static final String BASE_PATH = "/api/v1/payments";
    static final Duration GATE_TIMEOUT = Duration.ofSeconds(10);
    static final int HTTP_WORKERS = 16;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration FORWARD_TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_BODY_BYTES = 16 * 1024;
    /** java.net.http 가 직접 정하는 header 는 전달하지 않는다. */
    private static final Set<String> RESTRICTED_HEADERS = Set.of("connection", "content-length", "expect", "host",
        "upgrade", "transfer-encoding", "keep-alive");

    private final HttpServer server;
    private final ExecutorService workers;
    private final HttpClient forwarder;
    private final String pgBaseUrl;
    private final ScenarioRegistry scenarios;
    private final GateRegistry gates;
    private final RequestJournal journal;
    private final LifecycleGuard lifecycle;
    private final String workerThreadNamePrefix;

    private PgFaultProxy(HttpServer server, ExecutorService workers, HttpClient forwarder, String pgBaseUrl,
                         ScenarioRegistry scenarios, GateRegistry gates, RequestJournal journal, LifecycleGuard lifecycle,
                         String workerThreadNamePrefix) {
        this.server = server;
        this.workers = workers;
        this.forwarder = forwarder;
        this.pgBaseUrl = pgBaseUrl;
        this.scenarios = scenarios;
        this.gates = gates;
        this.journal = journal;
        this.lifecycle = lifecycle;
        this.workerThreadNamePrefix = workerThreadNamePrefix;
    }

    static PgFaultProxy start(String name, String pgBaseUrl, ScenarioRegistry scenarios, GateRegistry gates,
                              RequestJournal journal, LifecycleGuard lifecycle) {
        String workerThreadNamePrefix = name + "-proxy-";
        HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException("PG 프록시 시작 실패", e);
        }
        AtomicInteger sequence = new AtomicInteger();
        ExecutorService workers = Executors.newFixedThreadPool(HTTP_WORKERS, runnable -> {
            Thread thread = new Thread(runnable, workerThreadNamePrefix + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        HttpClient forwarder = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(FORWARD_TIMEOUT)
            .build();
        PgFaultProxy proxy = new PgFaultProxy(server, workers, forwarder, pgBaseUrl, scenarios, gates, journal, lifecycle,
            workerThreadNamePrefix);
        server.createContext("/", proxy::handle);
        server.setExecutor(workers);
        server.start();
        return proxy;
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    String workerThreadNamePrefix() {
        return workerThreadNamePrefix;
    }

    private void handle(HttpExchange exchange) {
        if (!lifecycle.tryEnterRequest()) {
            exchange.close();
            return;
        }
        try {
            PgOperation operation = classify(exchange.getRequestMethod(), exchange.getRequestURI().getRawPath());
            RequestJournal.Entry entry = operation == null ? null : journal.begin(operation);
            byte[] body;
            try {
                body = readBody(exchange);
            } catch (IOException e) {
                complete(entry, null, ResponseFailure.IO_ERROR);
                return;
            }
            Response pgResponse;
            try {
                pgResponse = forward(exchange, body);
            } catch (IOException e) {
                // PG 가 떠 있지 않은 테스트 장치 오류다. 업무 응답처럼 보이지 않게 502 로 알린다.
                pgResponse = new Response(502, "PG 전달 실패".getBytes(StandardCharsets.UTF_8), Map.of());
            }
            if (entry == null) {
                send(exchange, pgResponse, null);
                return;
            }
            if (pgResponse.status() == 400) {
                send(exchange, pgResponse, entry);
                return;
            }
            String target = targetOf(operation, exchange, body);
            journal.target(entry, target);
            if (operation == PgOperation.CREATE && (pgResponse.status() == 200 || pgResponse.status() == 202)) {
                journal.transactionCreated(entry, transactionKeyOf(pgResponse.body()));
            }
            ResponsePlan plan = target == null ? ResponsePlan.normal()
                : scenarios.poll(operation, target).orElseGet(ResponsePlan::normal);
            deliver(exchange, pgResponse, plan, entry);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            exchange.close();
            lifecycle.exitRequest();
        }
    }

    private static PgOperation classify(String method, String path) {
        String keyPrefix = BASE_PATH + "/";
        if (BASE_PATH.equals(path)) {
            return switch (method) {
                case "POST" -> PgOperation.CREATE;
                case "GET" -> PgOperation.LOOKUP_ORDER;
                default -> null;
            };
        }
        if (path.startsWith(keyPrefix) && path.length() > keyPrefix.length()
            && path.indexOf('/', keyPrefix.length()) < 0 && "GET".equals(method)) {
            return PgOperation.LOOKUP_TRANSACTION;
        }
        return null;
    }

    private Response forward(HttpExchange exchange, byte[] body) throws IOException, InterruptedException {
        URI uri = exchange.getRequestURI();
        String rawQuery = uri.getRawQuery();
        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create(pgBaseUrl + uri.getRawPath() + (rawQuery == null ? "" : "?" + rawQuery)))
            .timeout(FORWARD_TIMEOUT)
            .method(exchange.getRequestMethod(), body.length == 0
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(body));
        exchange.getRequestHeaders().forEach((name, values) -> {
            if (!RESTRICTED_HEADERS.contains(name.toLowerCase())) {
                values.forEach(value -> request.header(name, value));
            }
        });
        HttpResponse<byte[]> response = forwarder.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        response.headers().firstValue("Content-Type").ifPresent(value -> headers.put("Content-Type", value));
        return new Response(response.statusCode(), response.body(), headers);
    }

    private void deliver(HttpExchange exchange, Response pgResponse, ResponsePlan plan, RequestJournal.Entry entry)
        throws InterruptedException {
        switch (plan.effectiveReply()) {
            case NORMAL -> send(exchange, pgResponse, entry);
            case RAW -> send(exchange, new Response(plan.httpStatus(), plan.body().getBytes(StandardCharsets.UTF_8),
                plan.headers() == null ? Map.of() : plan.headers()), entry);
            case DELAY -> {
                if (lifecycle.awaitClosing(plan.delayMs())) {
                    complete(entry, null, ResponseFailure.ABORTED_BY_CLOSE);
                } else {
                    send(exchange, pgResponse, entry);
                }
            }
            case GATE -> {
                boolean released = gates.await(plan.gateId(), GATE_TIMEOUT);
                if (lifecycle.isClosing()) {
                    complete(entry, null, ResponseFailure.ABORTED_BY_CLOSE);
                } else if (!released) {
                    complete(entry, null, ResponseFailure.GATE_TIMEOUT);
                } else {
                    send(exchange, pgResponse, entry);
                }
            }
            case CLOSE_EARLY -> sendPartially(exchange, pgResponse, entry);
        }
    }

    /** 헤더를 보내지 않고 exchange 를 닫으면 JDK HttpServer 는 응답 없이 연결을 닫는다. */
    private void complete(RequestJournal.Entry entry, Integer responseStatus, ResponseFailure failure) {
        if (entry != null) {
            journal.complete(entry, responseStatus, failure);
        }
    }

    private void send(HttpExchange exchange, Response response, RequestJournal.Entry entry) {
        try {
            response.headers().forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
            byte[] body = response.body();
            exchange.sendResponseHeaders(response.status(), body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            }
            complete(entry, response.status(), null);
        } catch (IOException e) {
            complete(entry, response.status(), ResponseFailure.IO_ERROR);
        }
    }

    private void sendPartially(HttpExchange exchange, Response response, RequestJournal.Entry entry) {
        byte[] body = response.body();
        try {
            response.headers().forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
            exchange.sendResponseHeaders(response.status(), body.length);
            OutputStream out = exchange.getResponseBody();
            out.write(body, 0, body.length / 2);
            out.flush();
        } catch (IOException e) {
            // 클라이언트가 먼저 끊은 경우. 아래 close 로 정리한다.
        }
        // 응답 본문 스트림을 먼저 닫으면 JDK 21 HttpServer 는 "insufficient bytes" 예외만 내고 연결을 열어 둔다.
        // exchange 를 먼저 닫아야 그 예외가 내부에서 연결 종료로 이어져 클라이언트가 수신 도중 I/O 오류를 본다.
        exchange.close();
        complete(entry, response.status(), ResponseFailure.CLOSED_EARLY);
    }

    /** 5주차 fixture 와 같이, 생성은 본문 orderId, 거래 조회는 경로의 거래 키, 주문 조회는 orderId query 하나가 대상이다. */
    private static String targetOf(PgOperation operation, HttpExchange exchange, byte[] body) {
        return switch (operation) {
            case CREATE -> orderIdOf(body);
            case LOOKUP_TRANSACTION -> exchange.getRequestURI().getRawPath().substring(BASE_PATH.length() + 1);
            case LOOKUP_ORDER -> singleOrderId(exchange.getRequestURI().getRawQuery());
        };
    }

    private static String orderIdOf(byte[] body) {
        try {
            JsonNode orderId = MAPPER.readTree(body).get("orderId");
            return orderId != null && orderId.isTextual() ? orderId.textValue() : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static String singleOrderId(String rawQuery) {
        try {
            return decodeSingleOrderId(rawQuery);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String decodeSingleOrderId(String rawQuery) {
        List<String> values = new ArrayList<>();
        if (rawQuery != null) {
            for (String pair : rawQuery.split("&", -1)) {
                String[] nameAndValue = pair.split("=", 2);
                if ("orderId".equals(URLDecoder.decode(nameAndValue[0], StandardCharsets.UTF_8))) {
                    values.add(nameAndValue.length == 2 ? URLDecoder.decode(nameAndValue[1], StandardCharsets.UTF_8) : "");
                }
            }
        }
        return values.size() == 1 && !values.get(0).isEmpty() ? values.get(0) : null;
    }

    private static String transactionKeyOf(byte[] body) {
        try {
            JsonNode key = MAPPER.readTree(body).get("transactionKey");
            return key != null && key.isTextual() ? key.textValue() : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static byte[] readBody(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            return in.readNBytes(MAX_BODY_BYTES + 1);
        }
    }

    /** 신규 접수는 lifecycle 이 이미 막았다. 서버를 멈추고 worker 를 정리한다. */
    void stop() {
        server.stop(0);
        workers.shutdown();
        forwarder.shutdownNow();
    }

    boolean awaitTermination(Duration timeout) throws InterruptedException {
        if (workers.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            return true;
        }
        workers.shutdownNow();
        return workers.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    boolean isTerminated() {
        return workers.isTerminated();
    }

    private record Response(int status, byte[] body, Map<String, String> headers) {
    }
}
