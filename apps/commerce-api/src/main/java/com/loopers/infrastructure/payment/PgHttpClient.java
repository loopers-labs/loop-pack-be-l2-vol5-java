package com.loopers.infrastructure.payment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.payment.pg.PgPaymentRequestCommand;
import com.loopers.application.payment.pg.PgPaymentRequestResult;
import com.loopers.application.payment.pg.PgTransactionInfo;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.SocketTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * 로컬 테스트 PG 의 HTTP 호출. 요청 구성과 응답 해석을 이 클래스가 숨긴다. 조회에만 재시도·회로 차단을 걸고(다른 빈이 호출해야
 * 프록시가 적용된다), 결제 요청은 timeout 만 두며 어떤 결과든 예외 없이 돌려준다.
 */
@Slf4j
@Component
public class PgHttpClient {

    /**
     * 조회의 Retry·CircuitBreaker 인스턴스 이름. 설정은 {@code resilience4j.*.instances.pgLookup} 이다.
     */
    public static final String LOOKUP_RESILIENCE = "pgLookup";

    private static final String PAYMENTS_PATH = "/api/v1/payments";
    private static final int MAX_BODY_BYTES = 64 * 1024;
    private static final String CREATE_RESULT_EVENT = "payment.create.result";
    private static final String CAUSE_IO = "IO";
    private static final String CAUSE_TIMEOUT = "TIMEOUT";
    private static final String CAUSE_BAD_RESPONSE = "BAD_RESPONSE";

    private final PaymentPgProperties properties;
    private final ObjectMapper objectMapper;
    private final PgResponseParser parser;
    private final HttpClient httpClient;
    private final RestClient createClient;
    private final RestClient lookupClient;

    // 자동 설정된 builder 를 받아야 observation 이 붙어 PG 요청에 traceparent 가 실린다.
    public PgHttpClient(PaymentPgProperties properties, ObjectMapper objectMapper, RestClient.Builder restClientBuilder) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.parser = new PgResponseParser(objectMapper);
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.connectTimeout())
                .build();
        this.createClient = restClient(restClientBuilder, properties.createReadTimeout());
        this.lookupClient = restClient(restClientBuilder, properties.lookupReadTimeout());
    }

    private RestClient restClient(RestClient.Builder builder, Duration readTimeout) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);
        return builder.clone()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .defaultHeader("X-USER-ID", properties.userId())
                .build();
    }

    @PreDestroy
    public void shutdown() {
        httpClient.shutdownNow();
    }

    /**
     * 결제를 한 번 요청한다. 어떤 결과든 예외 없이 {@link PgPaymentRequestResult} 로 돌려준다.
     */
    public PgPaymentRequestResult requestPayment(PgPaymentRequestCommand request) {
        RawResponse response;
        try {
            response = exchange(createClient.post()
                    .uri(PAYMENTS_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(createPayload(request)));
        } catch (RestClientException e) {
            return unknown(request, causeOf(e), "요청 중 I/O 오류: " + e.getClass().getSimpleName());
        }

        // 202 + 요청과 맞는 PENDING 거래면 수락, 4조건을 모두 만족한 422 면 확정 거절이다. 나머지는 거래 생성 여부를 알 수 없다.
        if (response.is(HttpStatus.ACCEPTED)) {
            Optional<PgTransactionInfo> accepted = parser.acceptedTransaction(response.body(), request);
            if (accepted.isEmpty()) {
                return unknown(request, CAUSE_BAD_RESPONSE, "202 응답 본문이 요청과 맞지 않음");
            }
            createResult(request, "ACCEPTED").addKeyValue("transactionKey", accepted.get().transactionKey())
                    .log("PG 결제 요청 수락");
            return PgPaymentRequestResult.accepted(accepted.get());
        }
        if (response.is(HttpStatus.UNPROCESSABLE_ENTITY) && parser.isConfirmedRejection(response.body(), request)) {
            createResult(request, "REJECTED").log("PG 결제 요청 확정 거절");
            return PgPaymentRequestResult.rejectedWithoutTransaction();
        }
        return unknown(request, CAUSE_BAD_RESPONSE, "생성 응답 상태 " + response.status().value());
    }

    /**
     * 거래 키로 한 건을 조회한다. PG 가 모르는 키(404)이면 비어 있다. 조회하지 못하면 예외를 던진다.
     */
    @Retry(name = LOOKUP_RESILIENCE)
    @CircuitBreaker(name = LOOKUP_RESILIENCE)
    public Optional<PgTransactionInfo> getTransactionByKey(String transactionKey) {
        RawResponse response = exchangeForLookup(lookupClient.get()
                .uri(PAYMENTS_PATH + "/{transactionKey}", transactionKey)
                .accept(MediaType.APPLICATION_JSON));
        if (response.is(HttpStatus.NOT_FOUND)) {
            return Optional.empty();
        }
        return Optional.of(parser.transaction(requireOkBody(response)));
    }

    /**
     * 주문 식별자로 거래 목록을 조회한다. 거래가 없으면 빈 목록이다. 조회하지 못하면 예외를 던진다.
     */
    @Retry(name = LOOKUP_RESILIENCE)
    @CircuitBreaker(name = LOOKUP_RESILIENCE)
    public List<PgTransactionInfo> getTransactionsByOrder(String providerOrderId) {
        RawResponse response = exchangeForLookup(lookupClient.get()
                .uri(uriBuilder -> uriBuilder.path(PAYMENTS_PATH).queryParam("orderId", "{orderId}").build(providerOrderId))
                .accept(MediaType.APPLICATION_JSON));
        return parser.transactions(requireOkBody(response));
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

    // 거래가 생겼는지 모르는 결과. 사유는 결과 타입에 담지 않고 로그로만 남긴다. 원인 분류는 응답 유실 진단의 근거다.
    private PgPaymentRequestResult unknown(PgPaymentRequestCommand request, String cause, String reason) {
        log.atWarn()
                .addKeyValue("event", CREATE_RESULT_EVENT)
                .addKeyValue("providerOrderId", request.providerOrderId())
                .addKeyValue("result", "UNKNOWN")
                .addKeyValue("cause", cause)
                .log("PG 결제 요청 결과 미확인: cause={}, {}", cause, reason);
        return PgPaymentRequestResult.unknown();
    }

    private static LoggingEventBuilder createResult(PgPaymentRequestCommand request, String result) {
        return log.atInfo()
                .addKeyValue("event", CREATE_RESULT_EVENT)
                .addKeyValue("providerOrderId", request.providerOrderId())
                .addKeyValue("result", result);
    }

    private static String causeOf(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException) {
                return CAUSE_TIMEOUT;
            }
        }
        return CAUSE_IO;
    }

    private RawResponse exchangeForLookup(RestClient.RequestHeadersSpec<?> request) {
        try {
            return exchange(request);
        } catch (RestClientException e) {
            throw new PgStatusLookupFailedException("조회 중 I/O 오류: " + e.getClass().getSimpleName(), e);
        }
    }

    private RawResponse exchange(RestClient.RequestHeadersSpec<?> request) {
        return request.exchange((httpRequest, httpResponse) -> new RawResponse(
                httpResponse.getStatusCode(),
                new String(httpResponse.getBody().readNBytes(MAX_BODY_BYTES), StandardCharsets.UTF_8)
        ));
    }

    private String requireOkBody(RawResponse response) {
        if (!response.is(HttpStatus.OK)) {
            throw new PgStatusLookupFailedException("조회 응답 상태 " + response.status().value());
        }
        return response.body();
    }


    private record RawResponse(HttpStatusCode status, String body) {
        boolean is(HttpStatus expected) {
            return status.isSameCodeAs(expected);
        }
    }
}
