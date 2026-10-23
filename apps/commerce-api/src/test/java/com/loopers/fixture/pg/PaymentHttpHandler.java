package com.loopers.fixture.pg;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 처리 순서(2.10): 수신 기록 → 헤더·입력 검사 → plan 하나 소비 → 거래 효과 또는 조회 snapshot → 응답 제어.
 * 거래 효과를 응답 제어보다 먼저 만들므로 응답이 지연·유실돼도 이미 만든 거래는 남는다.
 * 헤더·입력 거절은 plan 을 소비하지 않는 400 이다. 5.5 의 422 는 효과 0건 증거로 쓰이므로 REJECT_BEFORE_CREATE 에만 쓴다.
 * 카드 값은 검사 후 버리며 저장·journal·응답에 남기지 않는다.
 */
final class PaymentHttpHandler implements HttpHandler {

    static final String BASE_PATH = "/api/v1/payments";
    static final String PROVIDER_USER_HEADER = "X-USER-ID";
    static final String PROVIDER_USER_ID = "135135";
    static final Duration GATE_TIMEOUT = Duration.ofSeconds(10);

    private static final String CARD_TYPE = "TEST";
    private static final String CARD_NO = "0000-0000-0000-0000";
    private static final String CALLBACK_HOST = "127.0.0.1";
    private static final String CALLBACK_PATH = "/api/v1/payments/callback";
    private static final String REJECTED_BEFORE_CREATE = "TEST_PG_REJECTED_BEFORE_CREATE";
    private static final int MIN_ORDER_ID_LENGTH = 6;
    private static final int MAX_BODY_BYTES = 16 * 1024;
    private static final Pattern TRANSACTION_KEY = Pattern.compile("[A-Za-z0-9_-]{1,255}");
    private static final Map<String, String> JSON_HEADERS = Map.of("Content-Type", "application/json");

    private final InMemoryTransactionStore store;
    private final ScenarioRegistry scenarios;
    private final GateRegistry gates;
    private final RequestJournal journal;
    private final LifecycleGuard lifecycle;
    private final ObjectMapper objectMapper;

    PaymentHttpHandler(InMemoryTransactionStore store, ScenarioRegistry scenarios, GateRegistry gates,
                       RequestJournal journal, LifecycleGuard lifecycle, ObjectMapper objectMapper) {
        this.store = store;
        this.scenarios = scenarios;
        this.gates = gates;
        this.journal = journal;
        this.lifecycle = lifecycle;
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(HttpExchange exchange) {
        if (!lifecycle.tryEnterRequest()) {
            exchange.close();
            return;
        }
        try {
            dispatch(exchange);
        } finally {
            exchange.close();
            lifecycle.exitRequest();
        }
    }

    private void dispatch(HttpExchange exchange) {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getRawPath();
        String keyPrefix = BASE_PATH + "/";
        if (BASE_PATH.equals(path)) {
            if ("POST".equals(method)) {
                handlePayment(exchange, PgOperation.CREATE, null);
            } else if ("GET".equals(method)) {
                handlePayment(exchange, PgOperation.LOOKUP_ORDER, null);
            } else {
                sendUnjournaled(exchange, error(405, "METHOD_NOT_ALLOWED", "지원하지 않는 메서드"));
            }
        } else if (path.startsWith(keyPrefix) && path.length() > keyPrefix.length()
            && path.indexOf('/', keyPrefix.length()) < 0) {
            if ("GET".equals(method)) {
                handlePayment(exchange, PgOperation.LOOKUP_TRANSACTION, path.substring(keyPrefix.length()));
            } else {
                sendUnjournaled(exchange, error(405, "METHOD_NOT_ALLOWED", "지원하지 않는 메서드"));
            }
        } else {
            sendUnjournaled(exchange, error(404, "NOT_FOUND", "없는 경로"));
        }
    }

    private void handlePayment(HttpExchange exchange, PgOperation operation, String transactionKey) {
        RequestJournal.Entry entry = journal.begin(operation);
        Prepared prepared;
        try {
            requireProviderUser(exchange);
            prepared = switch (operation) {
                case CREATE -> create(exchange, entry);
                case LOOKUP_TRANSACTION -> lookupByKey(transactionKey, entry);
                case LOOKUP_ORDER -> lookupByOrder(exchange, entry);
            };
        } catch (InvalidRequestException e) {
            prepared = new Prepared(error(400, e.code, e.getMessage()), ResponsePlan.normal());
        } catch (IOException e) {
            journal.complete(entry, null, ResponseFailure.IO_ERROR);
            return;
        } catch (RuntimeException e) {
            prepared = new Prepared(error(500, "INTERNAL_ERROR", "테스트 PG 처리 오류"), ResponsePlan.normal());
        }
        deliver(exchange, prepared, entry);
    }

    private Prepared create(HttpExchange exchange, RequestJournal.Entry entry) throws IOException {
        CreateRequest request = parseCreateRequest(readBody(exchange));
        journal.target(entry, request.orderId());
        ResponsePlan plan = consumePlan(PgOperation.CREATE, request.orderId());
        CreateOutcome outcome = plan.effectiveCreateOutcome();
        if (outcome == CreateOutcome.REJECT_BEFORE_CREATE) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("code", REJECTED_BEFORE_CREATE);
            body.put("orderId", request.orderId());
            body.put("amount", request.amount());
            body.put("transactionCreated", false);
            return new Prepared(json(422, body), plan);
        }
        boolean success = outcome == CreateOutcome.SUCCESS;
        StoredTransaction transaction = store.create(request.orderId(), request.amount(), request.callbackUrl(),
            success ? PaymentStatus.SUCCESS : PaymentStatus.PENDING);
        journal.transactionCreated(entry, transaction.transactionKey());
        return new Prepared(json(success ? 200 : 202, transaction.toSnapshot()), plan);
    }

    private Prepared lookupByKey(String transactionKey, RequestJournal.Entry entry) {
        journal.target(entry, transactionKey);
        ResponsePlan plan = consumePlan(PgOperation.LOOKUP_TRANSACTION, transactionKey);
        Optional<StoredTransaction> transaction = TRANSACTION_KEY.matcher(transactionKey).matches()
            ? store.findByKey(transactionKey)
            : Optional.empty();
        Response response = transaction
            .map(found -> json(200, found.toSnapshot()))
            .orElseGet(() -> error(404, "TRANSACTION_NOT_FOUND", "없는 거래"));
        return new Prepared(response, plan);
    }

    private Prepared lookupByOrder(HttpExchange exchange, RequestJournal.Entry entry) {
        String orderId = singleQueryParameter(exchange.getRequestURI().getRawQuery(), "orderId");
        journal.target(entry, orderId);
        ResponsePlan plan = consumePlan(PgOperation.LOOKUP_ORDER, orderId);
        List<TransactionSnapshot> transactions = store.findByOrderId(orderId).stream()
            .map(StoredTransaction::toSnapshot)
            .toList();
        return new Prepared(json(200, transactions), plan);
    }

    private ResponsePlan consumePlan(PgOperation operation, String target) {
        return scenarios.poll(operation, target).orElseGet(ResponsePlan::normal);
    }

    private void deliver(HttpExchange exchange, Prepared prepared, RequestJournal.Entry entry) {
        ResponsePlan plan = prepared.plan();
        try {
            switch (plan.effectiveReply()) {
                case NORMAL -> send(exchange, prepared.response(), entry);
                case RAW -> send(exchange, new Response(plan.httpStatus(),
                    plan.body().getBytes(StandardCharsets.UTF_8), plan.headers() == null ? Map.of() : plan.headers()), entry);
                case DELAY -> {
                    if (lifecycle.awaitClosing(plan.delayMs())) {
                        abort(entry, ResponseFailure.ABORTED_BY_CLOSE);
                    } else {
                        send(exchange, prepared.response(), entry);
                    }
                }
                case GATE -> {
                    boolean released = gates.await(plan.gateId(), GATE_TIMEOUT);
                    if (lifecycle.isClosing()) {
                        abort(entry, ResponseFailure.ABORTED_BY_CLOSE);
                    } else if (!released) {
                        abort(entry, ResponseFailure.GATE_TIMEOUT);
                    } else {
                        send(exchange, prepared.response(), entry);
                    }
                }
                case CLOSE_EARLY -> sendPartially(exchange, prepared.response(), entry);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            abort(entry, ResponseFailure.ABORTED_BY_CLOSE);
        }
    }

    /** 헤더를 보내지 않고 exchange 를 닫으면 JDK HttpServer 는 응답 없이 연결을 닫는다. */
    private void abort(RequestJournal.Entry entry, ResponseFailure failure) {
        journal.complete(entry, null, failure);
    }

    private void send(HttpExchange exchange, Response response, RequestJournal.Entry entry) {
        try {
            write(exchange, response);
            journal.complete(entry, response.status(), null);
        } catch (IOException e) {
            journal.complete(entry, response.status(), ResponseFailure.IO_ERROR);
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
        journal.complete(entry, response.status(), ResponseFailure.CLOSED_EARLY);
    }

    private static void sendUnjournaled(HttpExchange exchange, Response response) {
        try {
            write(exchange, response);
        } catch (IOException e) {
            // 계약 밖 요청의 응답 실패는 관측 대상이 아니다.
        }
    }

    private static void write(HttpExchange exchange, Response response) throws IOException {
        response.headers().forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
        byte[] body = response.body();
        exchange.sendResponseHeaders(response.status(), body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
    }

    private void requireProviderUser(HttpExchange exchange) {
        List<String> values = exchange.getRequestHeaders().get(PROVIDER_USER_HEADER);
        if (values == null || values.size() != 1 || !PROVIDER_USER_ID.equals(values.get(0))) {
            throw new InvalidRequestException("INVALID_PROVIDER_USER", "X-USER-ID 헤더는 고정 제공자 계정 하나만 허용");
        }
    }

    private byte[] readBody(HttpExchange exchange) throws IOException {
        try (InputStream body = exchange.getRequestBody()) {
            byte[] bytes = body.readNBytes(MAX_BODY_BYTES + 1);
            if (bytes.length > MAX_BODY_BYTES) {
                throw invalid("요청 본문이 너무 큼");
            }
            return bytes;
        }
    }

    private CreateRequest parseCreateRequest(byte[] body) {
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (IOException e) {
            throw invalid("요청 본문은 JSON 객체여야 함");
        }
        if (root == null || !root.isObject()) {
            throw invalid("요청 본문은 JSON 객체여야 함");
        }

        String orderId = requireText(root, "orderId");
        if (orderId.length() < MIN_ORDER_ID_LENGTH) {
            throw invalid("orderId 는 6자 이상 문자열");
        }
        if (!CARD_TYPE.equals(requireText(root, "cardType"))) {
            throw invalid("cardType 은 합성 값 TEST 만 허용");
        }
        if (!CARD_NO.equals(requireText(root, "cardNo"))) {
            throw invalid("cardNo 는 합성 값만 허용");
        }
        JsonNode amount = root.get("amount");
        if (amount == null || !amount.isIntegralNumber() || !amount.canConvertToLong() || amount.longValue() <= 0) {
            throw invalid("amount 는 양의 원화 정수");
        }
        String callbackUrl = requireText(root, "callbackUrl");
        requireLocalCallbackUrl(callbackUrl);
        return new CreateRequest(orderId, amount.longValue(), callbackUrl);
    }

    private static String requireText(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || !node.isTextual()) {
            throw invalid(field + " 는 문자열 필수 필드");
        }
        return node.textValue();
    }

    /** 임의 외부 주소로 콜백을 보내지 않도록 로컬 커머스 콜백 경로만 받는다(5.7). */
    private static void requireLocalCallbackUrl(String callbackUrl) {
        URI uri;
        try {
            uri = new URI(callbackUrl);
        } catch (URISyntaxException e) {
            throw invalid("callbackUrl 형식 오류");
        }
        boolean allowed = "http".equals(uri.getScheme())
            && uri.getRawUserInfo() == null
            && CALLBACK_HOST.equals(uri.getHost())
            && uri.getPort() >= 1 && uri.getPort() <= 65535
            && CALLBACK_PATH.equals(uri.getRawPath())
            && uri.getRawQuery() == null
            && uri.getRawFragment() == null;
        if (!allowed) {
            throw invalid("callbackUrl 은 http://127.0.0.1:{port}/api/v1/payments/callback 만 허용");
        }
    }

    private static String singleQueryParameter(String rawQuery, String name) {
        List<String> values = new ArrayList<>();
        if (rawQuery != null && !rawQuery.isEmpty()) {
            for (String pair : rawQuery.split("&", -1)) {
                String[] nameAndValue = pair.split("=", 2);
                if (name.equals(decode(nameAndValue[0]))) {
                    values.add(nameAndValue.length == 2 ? decode(nameAndValue[1]) : "");
                }
            }
        }
        if (values.size() != 1 || values.get(0).isEmpty()) {
            throw invalid(name + " query 는 비어 있지 않은 값 하나 필수");
        }
        return values.get(0);
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw invalid("query 인코딩 오류");
        }
    }

    private static InvalidRequestException invalid(String message) {
        return new InvalidRequestException("INVALID_REQUEST", message);
    }

    private Response error(int status, String code, String message) {
        return json(status, Map.of("code", code, "message", message));
    }

    private Response json(int status, Object body) {
        try {
            return new Response(status, objectMapper.writeValueAsBytes(body), JSON_HEADERS);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    private record CreateRequest(String orderId, long amount, String callbackUrl) {
    }

    private record Prepared(Response response, ResponsePlan plan) {
    }

    private record Response(int status, byte[] body, Map<String, String> headers) {
    }

    private static final class InvalidRequestException extends RuntimeException {

        private final String code;

        InvalidRequestException(String code, String message) {
            super(message, null, false, false);
            this.code = code;
        }
    }
}
