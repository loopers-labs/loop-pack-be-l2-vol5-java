package com.loopers.infrastructure.payment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.payment.pg.PgPaymentRequestCommand;
import com.loopers.application.payment.pg.PgPaymentRequestResult;
import com.loopers.application.payment.pg.PgStatus;
import com.loopers.application.payment.pg.PgTransactionInfo;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 로컬 테스트 PG 의 HTTP 호출. 요청 구성과 응답 해석을 이 클래스가 숨긴다. 조회에만 재시도·회로 차단을 걸고(다른 빈이 호출해야
 * 프록시가 적용된다), 결제 요청은 timeout 만 두며 어떤 결과든 예외 없이 돌려준다.
 */
@Slf4j
@Component
public class PgHttpClient {

    /** 조회의 Retry·CircuitBreaker 인스턴스 이름. 설정은 {@code resilience4j.*.instances.pgLookup} 이다. */
    public static final String LOOKUP_RESILIENCE = "pgLookup";

    private static final String PAYMENTS_PATH = "/api/v1/payments";
    private static final String REJECTED_CODE = "TEST_PG_REJECTED_BEFORE_CREATE";
    private static final int MAX_BODY_BYTES = 64 * 1024;
    private static final String UNPARSEABLE_TRANSACTION = "해석할 수 없는 거래 조회 응답";
    private static final String UNPARSEABLE_TRANSACTION_LIST = "해석할 수 없는 거래 목록 응답";

    private final PaymentPgProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final RestClient createClient;
    private final RestClient lookupClient;

    public PgHttpClient(PaymentPgProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(properties.connectTimeout())
            .build();
        this.createClient = restClient(properties.createReadTimeout());
        this.lookupClient = restClient(properties.lookupReadTimeout());
    }

    private RestClient restClient(Duration readTimeout) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);
        return RestClient.builder()
            .baseUrl(properties.baseUrl())
            .requestFactory(requestFactory)
            .defaultHeader("X-USER-ID", properties.userId())
            .build();
    }

    @PreDestroy
    public void shutdown() {
        httpClient.shutdownNow();
    }

    /** 결제를 한 번 요청한다. 어떤 결과든 예외 없이 {@link PgPaymentRequestResult} 로 돌려준다. */
    public PgPaymentRequestResult requestPayment(PgPaymentRequestCommand request) {
        byte[] payload = createPayload(request);
        RawResponse response;
        try {
            response = send(createClient.post()
                .uri(PAYMENTS_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(payload));
        } catch (RestClientException e) {
            return unknown("요청 중 I/O 오류: " + e.getClass().getSimpleName());
        }
        return mapCreateResponse(request, response);
    }

    /** 거래 키로 한 건을 조회한다. PG 가 모르는 키(404)이면 비어 있다. 조회하지 못하면 예외를 던진다. */
    @Retry(name = LOOKUP_RESILIENCE)
    @CircuitBreaker(name = LOOKUP_RESILIENCE)
    public Optional<PgTransactionInfo> getTransactionByKey(String transactionKey) {
        RawResponse response = sendForLookup(lookupClient.get()
            .uri(PAYMENTS_PATH + "/{transactionKey}", transactionKey)
            .accept(MediaType.APPLICATION_JSON));
        if (response.is(HttpStatus.NOT_FOUND)) {
            return Optional.empty();
        }
        requireOk(response);
        return Optional.of(requireParsed(parseTransaction(readTree(response.body())), UNPARSEABLE_TRANSACTION));
    }

    /** 주문 식별자로 거래 목록을 조회한다. 거래가 없으면 빈 목록이다. 조회하지 못하면 예외를 던진다. */
    @Retry(name = LOOKUP_RESILIENCE)
    @CircuitBreaker(name = LOOKUP_RESILIENCE)
    public List<PgTransactionInfo> getTransactionsByOrder(String providerOrderId) {
        RawResponse response = sendForLookup(lookupClient.get()
            .uri(uriBuilder -> uriBuilder.path(PAYMENTS_PATH).queryParam("orderId", "{orderId}").build(providerOrderId))
            .accept(MediaType.APPLICATION_JSON));
        requireOk(response);
        return parseTransactionList(readTree(response.body()));
    }

    private byte[] createPayload(PgPaymentRequestCommand request) {
        try {
            return objectMapper.writeValueAsBytes(
                objectMapper.createObjectNode()
                    .put("orderId", request.providerOrderId())
                    .put("cardType", request.cardType())
                    .put("cardNo", request.cardNo())
                    .put("amount", request.amount())
                    .put("callbackUrl", properties.callbackUrl())
            );
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("결제 생성 요청을 직렬화할 수 없습니다.", e);
        }
    }

    // 202 + 요청과 맞는 PENDING 거래면 수락, 4조건을 모두 만족한 422 면 확정 거절이다. 나머지는 거래 생성 여부를 알 수 없다.
    private PgPaymentRequestResult mapCreateResponse(PgPaymentRequestCommand request, RawResponse response) {
        if (response.is(HttpStatus.ACCEPTED)) {
            Optional<PgTransactionInfo> transaction = parseTransaction(readTree(response.body()));
            if (transaction.isPresent() && matchesAcceptedRequest(request, transaction.get())) {
                return PgPaymentRequestResult.accepted(transaction.get());
            }
            return unknown("202 응답 본문이 요청과 맞지 않음");
        }
        if (response.is(HttpStatus.UNPROCESSABLE_ENTITY) && isConfirmedRejection(request, readTree(response.body()))) {
            return PgPaymentRequestResult.rejectedWithoutTransaction();
        }
        return unknown("생성 응답 상태 " + response.status().value());
    }

    // 거래가 생겼는지 모르는 결과. 사유는 결과 타입에 담지 않고 로그로만 남긴다.
    private PgPaymentRequestResult unknown(String reason) {
        log.warn("PG 결제 요청 결과 미확인: {}", reason);
        return PgPaymentRequestResult.unknown();
    }

    private boolean matchesAcceptedRequest(PgPaymentRequestCommand request, PgTransactionInfo transaction) {
        return transaction.status() == PgStatus.PENDING
            && transaction.providerOrderId().equals(request.providerOrderId())
            && transaction.amount() == request.amount();
    }

    // 정확한 code + transactionCreated=false + 요청 orderId·amount 일치. 하나라도 어긋나면 효과가 없다는 증거가 아니다.
    private boolean isConfirmedRejection(PgPaymentRequestCommand request, JsonNode body) {
        if (body == null || !body.isObject()) {
            return false;
        }
        return textEquals(body.get("code"), REJECTED_CODE)
            && isFalse(body.get("transactionCreated"))
            && textEquals(body.get("orderId"), request.providerOrderId())
            && longEquals(body.get("amount"), request.amount());
    }

    private List<PgTransactionInfo> parseTransactionList(JsonNode root) {
        if (root == null || !root.isArray()) {
            throw new PgStatusCheckFailedException(UNPARSEABLE_TRANSACTION_LIST);
        }
        List<PgTransactionInfo> transactions = new ArrayList<>();
        for (JsonNode element : root) {
            transactions.add(requireParsed(parseTransaction(element), UNPARSEABLE_TRANSACTION_LIST));
        }
        return transactions;
    }

    private PgTransactionInfo requireParsed(Optional<PgTransactionInfo> transaction, String message) {
        return transaction.orElseThrow(() -> new PgStatusCheckFailedException(message));
    }

    private Optional<PgTransactionInfo> parseTransaction(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Optional.empty();
        }
        JsonNode transactionKey = node.get("transactionKey");
        JsonNode orderId = node.get("orderId");
        JsonNode amount = node.get("amount");
        JsonNode status = node.get("status");
        if (!isText(transactionKey) || !isText(orderId) || !isLong(amount) || !isText(status)) {
            return Optional.empty();
        }
        try {
            return Optional.of(new PgTransactionInfo(
                transactionKey.asText(), orderId.asText(), amount.asLong(), PgStatus.valueOf(status.asText())
            ));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private boolean isText(JsonNode node) {
        return node != null && node.isTextual() && !node.asText().isBlank();
    }

    // 금액은 JSON 정수여야 한다. 문자열 "7000" 이나 소수는 받아들이지 않는다.
    private boolean isLong(JsonNode node) {
        return node != null && node.isIntegralNumber() && node.canConvertToLong();
    }

    private boolean textEquals(JsonNode node, String expected) {
        return node != null && node.isTextual() && node.asText().equals(expected);
    }

    private boolean longEquals(JsonNode node, long expected) {
        return isLong(node) && node.asLong() == expected;
    }

    private boolean isFalse(JsonNode node) {
        return node != null && node.isBoolean() && !node.asBoolean();
    }

    private JsonNode readTree(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(body);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private RawResponse send(RestClient.RequestHeadersSpec<?> request) {
        return request.exchange((httpRequest, httpResponse) -> raw(httpResponse.getStatusCode(), httpResponse.getBody()));
    }

    private RawResponse sendForLookup(RestClient.RequestHeadersSpec<?> request) {
        try {
            return send(request);
        } catch (RestClientException e) {
            throw new PgStatusCheckFailedException("조회 중 I/O 오류: " + e.getClass().getSimpleName(), e);
        }
    }

    private void requireOk(RawResponse response) {
        if (!response.is(HttpStatus.OK)) {
            throw new PgStatusCheckFailedException("조회 응답 상태 " + response.status().value());
        }
    }

    private static RawResponse raw(HttpStatusCode status, InputStream body) throws IOException {
        return new RawResponse(status, new String(body.readNBytes(MAX_BODY_BYTES), StandardCharsets.UTF_8));
    }

    private record RawResponse(HttpStatusCode status, String body) {
        boolean is(HttpStatus expected) {
            return status.isSameCodeAs(expected);
        }
    }
}
