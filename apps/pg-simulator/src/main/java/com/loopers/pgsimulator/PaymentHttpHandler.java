package com.loopers.pgsimulator;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 5.5 계약의 세 요청을 처리한다(설계 2.10). 처리 순서: 경로·메서드 분류 → 헤더·입력 검사 → 결과 정책 → 거래 효과 → 응답.
 * 거래를 저장한 뒤 응답하므로 응답 전송이 실패해도 거래는 남는다. 헤더·입력 거절은 정책을 호출하지 않는 400 이다.
 * 카드 값은 검사 후 버리며 저장·로그·응답에 남기지 않는다.
 */
final class PaymentHttpHandler implements HttpHandler {

    static final String BASE_PATH = "/api/v1/payments";
    static final String PROVIDER_USER_HEADER = "X-USER-ID";
    static final String PROVIDER_USER_ID = "135135";

    private static final Logger log = LoggerFactory.getLogger(PaymentHttpHandler.class);
    private static final String CARD_TYPE = "TEST";
    private static final String CARD_NO = "0000-0000-0000-0000";
    private static final String CALLBACK_HOST = "127.0.0.1";
    private static final String CALLBACK_PATH = "/api/v1/payments/callback";
    private static final String REJECTED_BEFORE_CREATE = "TEST_PG_REJECTED_BEFORE_CREATE";
    private static final int MIN_ORDER_ID_LENGTH = 6;
    private static final int MAX_BODY_BYTES = 16 * 1024;
    private static final Pattern TRANSACTION_KEY = Pattern.compile("[A-Za-z0-9_-]{1,255}");
    private static final Map<String, String> JSON_HEADERS = Map.of("Content-Type", "application/json");

    private final TransactionStore store;
    private final OutcomePolicy outcomePolicy;
    private final AutoSettlement autoSettlement;
    private final ObjectMapper objectMapper;

    PaymentHttpHandler(TransactionStore store, OutcomePolicy outcomePolicy, AutoSettlement autoSettlement,
                       ObjectMapper objectMapper) {
        this.store = store;
        this.outcomePolicy = outcomePolicy;
        this.autoSettlement = autoSettlement;
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(HttpExchange exchange) {
        try {
            Response response = dispatch(exchange);
            if (response != null) {
                send(exchange, response);
            }
        } finally {
            exchange.close();
        }
    }

    /** 응답 없이 닫아야 하면 null 이다. */
    private Response dispatch(HttpExchange exchange) {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getRawPath();
        String keyPrefix = BASE_PATH + "/";
        Operation operation;
        if (BASE_PATH.equals(path)) {
            operation = switch (method) {
                case "POST" -> Operation.CREATE;
                case "GET" -> Operation.LOOKUP_ORDER;
                default -> null;
            };
        } else if (path.startsWith(keyPrefix) && path.length() > keyPrefix.length()
            && path.indexOf('/', keyPrefix.length()) < 0) {
            operation = "GET".equals(method) ? Operation.LOOKUP_TRANSACTION : null;
        } else {
            return error(404, "NOT_FOUND", "없는 경로");
        }
        if (operation == null) {
            return error(405, "METHOD_NOT_ALLOWED", "지원하지 않는 메서드");
        }

        try {
            requireProviderUser(exchange);
            return switch (operation) {
                case CREATE -> create(exchange);
                case LOOKUP_TRANSACTION -> lookupByKey(path.substring(keyPrefix.length()));
                case LOOKUP_ORDER -> lookupByOrder(exchange);
            };
        } catch (InvalidRequestException e) {
            log.info("PG {} 거절: {}", operation, e.getMessage());
            return error(400, e.code, e.getMessage());
        } catch (IOException e) {
            // 요청 본문을 끝까지 읽지 못했다. 응답 없이 연결을 닫는다.
            log.info("PG {} 요청 수신 실패: {}", operation, e.getMessage());
            return null;
        } catch (RuntimeException e) {
            log.error("PG {} 처리 오류", operation, e);
            return error(500, "INTERNAL_ERROR", "PG 처리 오류");
        }
    }

    private Response create(HttpExchange exchange) throws IOException {
        CreateRequest request = parseCreateRequest(readBody(exchange));
        CreateOutcome outcome = outcomePolicy.decide(new CreateAttempt(request.orderId(), request.amount()));
        if (outcome == null) {
            throw new IllegalStateException("결과 정책이 null 을 반환함");
        }
        if (outcome == CreateOutcome.REJECT_BEFORE_CREATE) {
            log.info("PG 생성 전 거절: orderId={}, amount={}", request.orderId(), request.amount());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("code", REJECTED_BEFORE_CREATE);
            body.put("orderId", request.orderId());
            body.put("amount", request.amount());
            body.put("transactionCreated", false);
            return json(422, body);
        }
        boolean immediateSuccess = outcome == CreateOutcome.IMMEDIATE_SUCCESS;
        TransactionSnapshot transaction = store.create(request.orderId(), request.amount(), request.callbackUrl(),
            immediateSuccess ? PaymentStatus.SUCCESS : PaymentStatus.PENDING);
        log.info("PG 거래 생성: orderId={}, transactionKey={}, amount={}, outcome={}",
            transaction.orderId(), transaction.transactionKey(), transaction.amount(), outcome);
        autoSettlement.schedule(transaction.transactionKey(), outcome);
        return json(immediateSuccess ? 200 : 202, responseBody(transaction));
    }

    private Response lookupByKey(String transactionKey) {
        if (!TRANSACTION_KEY.matcher(transactionKey).matches()) {
            return error(404, "TRANSACTION_NOT_FOUND", "없는 거래");
        }
        return store.findByKey(transactionKey)
            .map(found -> json(200, responseBody(found)))
            .orElseGet(() -> error(404, "TRANSACTION_NOT_FOUND", "없는 거래"));
    }

    private Response lookupByOrder(HttpExchange exchange) {
        String orderId = singleQueryParameter(exchange.getRequestURI().getRawQuery(), "orderId");
        List<Map<String, Object>> transactions = store.findByOrderId(orderId).stream()
            .map(PaymentHttpHandler::responseBody)
            .toList();
        return json(200, transactions);
    }

    /** 응답 객체는 5.5 의 4필드다. 저장 callbackUrl 은 노출하지 않는다. */
    private static Map<String, Object> responseBody(TransactionSnapshot transaction) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("transactionKey", transaction.transactionKey());
        body.put("orderId", transaction.orderId());
        body.put("amount", transaction.amount());
        body.put("status", transaction.status());
        return body;
    }

    private static void send(HttpExchange exchange, Response response) {
        try {
            response.headers().forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
            byte[] body = response.body();
            exchange.sendResponseHeaders(response.status(), body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            }
        } catch (IOException e) {
            // 클라이언트가 먼저 끊은 경우다. 이미 만든 거래는 지우지 않는다.
            log.info("PG 응답 전송 실패: {}", e.getMessage());
        }
    }

    private static void requireProviderUser(HttpExchange exchange) {
        List<String> values = exchange.getRequestHeaders().get(PROVIDER_USER_HEADER);
        if (values == null || values.size() != 1 || !PROVIDER_USER_ID.equals(values.get(0))) {
            throw new InvalidRequestException("INVALID_PROVIDER_USER", "X-USER-ID 헤더는 고정 제공자 계정 하나만 허용");
        }
    }

    private static byte[] readBody(HttpExchange exchange) throws IOException {
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

    private enum Operation {
        CREATE,
        LOOKUP_TRANSACTION,
        LOOKUP_ORDER
    }

    private record CreateRequest(String orderId, long amount, String callbackUrl) {
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
