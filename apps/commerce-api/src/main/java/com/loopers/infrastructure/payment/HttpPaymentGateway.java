package com.loopers.infrastructure.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loopers.domain.payment.PaymentAttempt;
import com.loopers.domain.payment.PaymentCreateCommand;
import com.loopers.domain.payment.PaymentCreateResult;
import com.loopers.domain.payment.PaymentGateway;
import com.loopers.domain.payment.PaymentLookupResult;
import com.loopers.domain.payment.PaymentTransaction;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.springframework.http.HttpRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.StreamingHttpOutputMessage;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.Supplier;

/**
 * 로컬 테스트 PG 의 실제 HTTP adapter (설계 1-6 결정 ㉑~㉖, 7-3).
 * RestClient + JDK HttpClient(JdkClientHttpRequestFactory) 를 이 클래스 안에서 조립한다. 프록시를 쓰지 않고, HTTP/1.1 로 고정하며,
 * 연결 timeout 은 HttpClient 에, 응답 timeout 은 request factory 에 설정값으로 건다. 어떤 요청도 다시 보내지 않는다.
 * base URL·X-USER-ID·callback URL 은 설정값만 쓴다. HTTP 요청·응답 본문은 로그에 남기지 않는다 (요청 본문에 카드 번호가 있다).
 * 응답은 상태 코드와 본문 문자열로 받아 트리로 해석한다. transactionCreated 가 진짜 boolean 인지 보기 위해서다.
 * create: orderId·amount 일치가 확인된 202 + PENDING + 거래 키 → ACCEPTED,
 * orderId·amount 일치가 확인된 422 + 정확한 code + boolean false → REJECTED_NO_EFFECT,
 * 202·422 인데 orderId·amount 불일치 → UNKNOWN + 충돌 사유(key 없음), 그 밖의 응답·잘못된 JSON·timeout·연결 실패 → UNKNOWN.
 * 조회(목록·단건): 200 → FOUND + 거래, 단건 404 → FOUND + 0건, 그 밖의 응답·잘못된 JSON·timeout·연결 실패 → UNAVAILABLE(CALL_FAILED).
 * 조회 두 경로는 서킷브레이커 하나로 감싼다 (설계 5-7 결정 ㉗~㉜). UNAVAILABLE(CALL_FAILED) 결과만 실패 표본으로 세고 FOUND(빈 목록·404 포함)는 성공이다.
 * 서킷이 OPEN 이면 HTTP 호출 없이 UNAVAILABLE(CIRCUIT_OPEN) 을 돌려준다. create 는 서킷과 무관하다.
 * enabled=false 면 세 메서드 모두 IllegalStateException 을 던지고 서버에 요청을 보내지 않는다. 서킷보다 먼저 검사한다.
 */
@Component
@Slf4j
public class HttpPaymentGateway implements PaymentGateway {

    private static final String PAYMENTS_PATH = "/api/v1/payments";
    private static final String USER_ID_HEADER = "X-USER-ID";
    private static final String REJECTED_BEFORE_CREATE_CODE = "TEST_PG_REJECTED_BEFORE_CREATE";
    private static final int CAUSE_CHAIN_LIMIT = 5;
    private static final int ACCEPTED = 202;
    private static final int OK = 200;
    private static final int NOT_FOUND = 404;
    private static final int UNPROCESSABLE_CONTENT = 422;

    /**
     * 상태 코드와 본문 문자열. 본문이 없으면 빈 문자열이다.
     */
    private record RawResponse(int status, String body) {}

    private static final String LOOKUP_CIRCUIT_BREAKER_NAME = "payment-pg-lookup";

    private final PaymentGatewayProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private final CircuitBreaker lookupCircuitBreaker;

    /**
     * clock 은 서킷브레이커의 시계다 (OPEN 대기 판정). 운영은 Clock 빈, 계약 테스트는 앞당길 수 있는 시계를 넣는다.
     * 서킷 설정의 sliding-window-type 이 잘못된 값이면 여기서 IllegalArgumentException 으로 기동이 실패한다. 조용히 기본값으로 넘어가지 않는다.
     */
    public HttpPaymentGateway(PaymentGatewayProperties properties, ObjectMapper objectMapper, Clock clock) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.lookupCircuitBreaker = CircuitBreaker.of(
            LOOKUP_CIRCUIT_BREAKER_NAME, lookupCircuitBreakerConfig(properties.circuitBreaker(), clock));

        HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .proxy(HttpClient.Builder.NO_PROXY)
            .connectTimeout(properties.connectTimeout())
            .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        this.restClient = RestClient.builder()
            .baseUrl(properties.baseUrl())
            .defaultHeader(USER_ID_HEADER, properties.userId())
            .requestFactory(requestFactory)
            .build();
    }

    /**
     * POST /api/v1/payments. 본문은 orderId·cardType·cardNo·amount(정수)·callbackUrl(설정값). 1회만 보낸다.
     * 본문은 메시지 컨버터를 거치지 않고 바이트로 직접 쓴다. 컨버터 경로는 RestClient 가 DEBUG 레벨에서 본문 전체를 로그에 남기기 때문이다.
     */
    @Override
    public PaymentCreateResult create(PaymentCreateCommand command) {
        requireEnabled();
        byte[] body = createBody(command);
        RawResponse response;
        try {
            response = restClient.post()
                .uri(PAYMENTS_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .body((StreamingHttpOutputMessage.Body) outputStream -> outputStream.write(body))
                .exchange(HttpPaymentGateway::readRaw);
        } catch (RestClientException | CancellationException e) {
            // 요청은 보냈고 결과를 확인하지 못한 지점. 외부 거래가 생겼는지 알 수 없으므로 기존 UNKNOWN 경로로 수렴시킨다.
            // CancellationException 을 같이 잡는 이유: spring-web 6.2.5 의 JdkClientHttpRequest 는 timeout 처리 중
            // CompletableFuture 를 취소해 이 예외를 그대로 올릴 수 있다 (spring-framework#33973, 6.2.10 에서 수정).
            // 이 프로젝트에는 PG 호출을 의도적으로 취소하거나 인터럽트하는 경로가 없으므로, 여기서의 취소는 "결과 미확인"을 뜻한다.
            // RuntimeException·Exception·Throwable 전체를 잡지 않는다. 개발 오류나 설정 결함을 UNKNOWN 으로 숨기지 않기 위해서다.
            return unknownCreate(command.providerOrderId(), e);
        }
        PaymentCreateResult result = mapCreate(command, response);
        logCreateResult(command, result);
        return result;
    }

    /**
     * create 응답 한 건의 경계 결과. ACCEPTED 는 "거래 수락"이고 결제 성공이 아니다 (최종 결과는 조회·callback 으로 확인한다).
     * REJECTED_NO_EFFECT 는 업무 거절이라 INFO 로 남긴다. UNKNOWN 만 사람이 봐야 하는 비정상이다.
     */
    private void logCreateResult(PaymentCreateCommand command, PaymentCreateResult result) {
        switch (result.outcome()) {
            case ACCEPTED -> log.info("pg.create outcome=ACCEPTED providerOrderId={} transactionKey={}",
                command.providerOrderId(), result.transactionKey());
            case REJECTED_NO_EFFECT -> log.info("pg.create outcome=REJECTED_NO_EFFECT providerOrderId={}",
                command.providerOrderId());
            case UNKNOWN -> log.warn("pg.create outcome=UNKNOWN providerOrderId={} cause=unexpected-response conflict={}",
                command.providerOrderId(), result.conflictReason());
        }
    }

    /**
     * 결과를 확인하지 못한 create 를 UNKNOWN 으로 수렴시키고 증거를 남긴다.
     * 예외 타입과 cause chain 까지만 적는다. timeout 인지 거래가 생겼는지를 여기서 단정하지 않는다.
     * interrupted 는 "명시적 취소·인터럽트 정책"과 "결과 미확인"을 구분하기 위한 관측값이다. 이 프로젝트에는 그런 정책이 없다.
     */
    PaymentCreateResult unknownCreate(String providerOrderId, RuntimeException e) {
        log.warn("pg.create outcome=UNKNOWN providerOrderId={} exception={} causeChain={} interrupted={}",
            providerOrderId, e.getClass().getSimpleName(), causeChain(e), Thread.currentThread().isInterrupted());
        return PaymentCreateResult.unknown();
    }

    /**
     * 조회 결과를 확인하지 못한 경우 기존 UNAVAILABLE 로 수렴시키고 증거를 남긴다.
     */
    private PaymentLookupResult unavailableLookup(RuntimeException e) {
        log.warn("pg.lookup outcome=UNAVAILABLE exception={} causeChain={} interrupted={}",
            e.getClass().getSimpleName(), causeChain(e), Thread.currentThread().isInterrupted());
        return PaymentLookupResult.unavailable(PaymentLookupResult.UnavailableReason.CALL_FAILED);
    }

    /**
     * 예외의 cause chain 을 클래스 이름만으로 늘어놓는다. 메시지에는 주소·본문이 섞일 수 있어 남기지 않는다.
     * 가장 깊은 원인 하나만 보면 정보를 잃는다 (연결 거부가 ClosedChannelException 으로만 보이는 사례).
     */
    private static List<String> causeChain(Throwable e) {
        List<String> names = new ArrayList<>();
        Throwable cause = e;
        while (cause != null && names.size() < CAUSE_CHAIN_LIMIT) {
            names.add(cause.getClass().getSimpleName());
            cause = cause.getCause() == cause ? null : cause.getCause();
        }
        return names;
    }

    /**
     * GET /api/v1/payments?orderId={providerOrderId}. 쿼리는 URI builder 가 인코딩한다.
     */
    @Override
    public PaymentLookupResult findAllByOrderId(String providerOrderId) {
        requireEnabled();
        return logLookup("orderId", providerOrderId, guardedByCircuit(() -> lookUpList(providerOrderId)));
    }

    /**
     * GET /api/v1/payments/{transactionKey}. 200 → FOUND + 1건, 404 → FOUND + 0건, 그 밖의 실패 → UNAVAILABLE.
     */
    @Override
    public PaymentLookupResult findByTransactionKey(String transactionKey) {
        requireEnabled();
        return logLookup("transactionKey", transactionKey, guardedByCircuit(() -> lookUpSingle(transactionKey)));
    }

    /**
     * 권위 조회의 경계 결과. FOUND 는 거래 수(0 건 포함)를 남겨 "거래가 생겼는가"에 답한다.
     * 조회 불가·서킷 차단은 사람이 봐야 하는 비정상이므로 WARN 이다. 응답 본문은 남기지 않는다.
     */
    private PaymentLookupResult logLookup(String keyName, String keyValue, PaymentLookupResult result) {
        if (result.isUnavailable()) {
            log.warn("pg.lookup outcome=UNAVAILABLE {}={} reason={}", keyName, keyValue, result.reason());
        } else {
            log.info("pg.lookup outcome=FOUND {}={} count={} statuses={}", keyName, keyValue,
                result.transactions().size(),
                result.transactions().stream().map(transaction -> transaction.status().name()).toList());
        }
        return result;
    }

    /**
     * "HTTP 호출 + 해석" 전체를 서킷으로 감싼다. 호출을 막으면(OPEN) 서버에 요청을 보내지 않고 CIRCUIT_OPEN 을 돌려준다.
     */
    private PaymentLookupResult guardedByCircuit(Supplier<PaymentLookupResult> lookup) {
        try {
            return lookupCircuitBreaker.executeSupplier(lookup);
        } catch (CallNotPermittedException e) {
            return PaymentLookupResult.unavailable(PaymentLookupResult.UnavailableReason.CIRCUIT_OPEN);
        }
    }

    private static CircuitBreakerConfig lookupCircuitBreakerConfig(PaymentGatewayProperties.CircuitBreaker settings, Clock clock) {
        return CircuitBreakerConfig.custom()
            .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.valueOf(settings.slidingWindowType()))
            .slidingWindowSize(settings.slidingWindowSize())
            .minimumNumberOfCalls(settings.minimumNumberOfCalls())
            .failureRateThreshold(settings.failureRateThreshold())
            .waitDurationInOpenState(settings.waitDurationInOpenState())
            .permittedNumberOfCallsInHalfOpenState(settings.permittedNumberOfCallsInHalfOpenState())
            .recordResult(result -> result instanceof PaymentLookupResult lookup && lookup.isUnavailable())
            .clock(clock)
            .build();
    }

    private PaymentLookupResult lookUpList(String providerOrderId) {
        RawResponse response;
        try {
            response = restClient.get()
                .uri(uriBuilder -> uriBuilder.path(PAYMENTS_PATH).queryParam("orderId", providerOrderId).build())
                .exchange(HttpPaymentGateway::readRaw);
        } catch (RestClientException | CancellationException e) {
            return unavailableLookup(e);
        }
        if (response.status() != OK) {
            return PaymentLookupResult.unavailable(PaymentLookupResult.UnavailableReason.CALL_FAILED);
        }
        try {
            JsonNode body = objectMapper.readTree(response.body());
            if (!body.isArray()) {
                return PaymentLookupResult.unavailable(PaymentLookupResult.UnavailableReason.CALL_FAILED);
            }
            List<PaymentTransaction> transactions = new ArrayList<>();
            for (JsonNode node : body) {
                transactions.add(toTransaction(node));
            }
            return PaymentLookupResult.found(transactions);
        } catch (IOException | IllegalArgumentException e) {
            return PaymentLookupResult.unavailable(PaymentLookupResult.UnavailableReason.CALL_FAILED);
        }
    }

    private PaymentLookupResult lookUpSingle(String transactionKey) {
        RawResponse response;
        try {
            response = restClient.get()
                .uri(PAYMENTS_PATH + "/{transactionKey}", transactionKey)
                .exchange(HttpPaymentGateway::readRaw);
        } catch (RestClientException | CancellationException e) {
            return unavailableLookup(e);
        }
        if (response.status() == NOT_FOUND) {
            return PaymentLookupResult.found(List.of());
        }
        if (response.status() != OK) {
            return PaymentLookupResult.unavailable(PaymentLookupResult.UnavailableReason.CALL_FAILED);
        }
        try {
            return PaymentLookupResult.found(List.of(toTransaction(objectMapper.readTree(response.body()))));
        } catch (IOException | IllegalArgumentException e) {
            return PaymentLookupResult.unavailable(PaymentLookupResult.UnavailableReason.CALL_FAILED);
        }
    }

    private void requireEnabled() {
        if (!properties.enabled()) {
            throw new IllegalStateException(
                "PG adapter 가 비활성화돼 있다 (payment.pg.enabled=false). 테스트라면 FakePaymentGatewayConfig 를 @Import 했는지 확인한다.");
        }
    }

    private byte[] createBody(PaymentCreateCommand command) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("orderId", command.providerOrderId());
        body.put("cardType", command.cardType());
        body.put("cardNo", command.cardNo());
        body.put("amount", command.amount());
        body.put("callbackUrl", properties.callbackUrl());
        return body.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 202 수락과 422 확정 거절 모두 응답의 주문 ID와 금액이 요청과 일치해야 한다.
     * 유효한 값의 불일치는 충돌로 남기고, 필수 필드 부재나 형식 오류만 있으면 UNKNOWN 으로 둔다.
     */
    private PaymentCreateResult mapCreate(PaymentCreateCommand command, RawResponse response) {
        JsonNode body;
        try {
            body = objectMapper.readTree(response.body());
        } catch (IOException e) {
            return PaymentCreateResult.unknown();
        }
        if (body == null || !body.isObject()) {
            return PaymentCreateResult.unknown();
        }
        boolean rejection = response.status() == UNPROCESSABLE_CONTENT;
        if (rejection) {
            JsonNode transactionCreated = body.get("transactionCreated");
            boolean rejectedBeforeCreate = REJECTED_BEFORE_CREATE_CODE.equals(textOf(body, "code"))
                && transactionCreated != null && transactionCreated.isBoolean() && !transactionCreated.booleanValue();
            if (!rejectedBeforeCreate) {
                return PaymentCreateResult.unknown();
            }
        } else if (response.status() != ACCEPTED) {
            return PaymentCreateResult.unknown();
        }

        String orderId = textOf(body, "orderId");
        if (orderId != null && !orderId.equals(command.providerOrderId())) {
            return PaymentCreateResult.unknownWithConflict(PaymentAttempt.ConflictReason.ORDER_MISMATCH);
        }
        JsonNode amount = body.get("amount");
        boolean validAmount = amount != null && amount.isIntegralNumber() && amount.canConvertToLong();
        if (validAmount && amount.longValue() != command.amount()) {
            return PaymentCreateResult.unknownWithConflict(PaymentAttempt.ConflictReason.AMOUNT_MISMATCH);
        }
        if (orderId == null || !validAmount) {
            return PaymentCreateResult.unknown();
        }
        if (rejection) {
            return PaymentCreateResult.rejectedNoEffect();
        }
        String transactionKey = textOf(body, "transactionKey");
        if (transactionKey == null || !PaymentTransaction.Status.PENDING.name().equals(textOf(body, "status"))) {
            return PaymentCreateResult.unknown();
        }
        return PaymentCreateResult.accepted(transactionKey);
    }

    private static PaymentTransaction toTransaction(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("거래 응답이 객체가 아니다.");
        }
        String transactionKey = textOf(node, "transactionKey");
        String orderId = textOf(node, "orderId");
        String status = textOf(node, "status");
        JsonNode amount = node.get("amount");
        boolean validAmount = amount != null && amount.isIntegralNumber() && amount.canConvertToLong();
        if (transactionKey == null || orderId == null || status == null || !validAmount) {
            throw new IllegalArgumentException("거래 응답의 필수 값이 없거나 유효하지 않다.");
        }
        return new PaymentTransaction(transactionKey, orderId, amount.longValue(), PaymentTransaction.Status.valueOf(status));
    }

    private static String textOf(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    private static RawResponse readRaw(HttpRequest request, ClientHttpResponse response) throws IOException {
        return new RawResponse(
            response.getStatusCode().value(), new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8));
    }
}
