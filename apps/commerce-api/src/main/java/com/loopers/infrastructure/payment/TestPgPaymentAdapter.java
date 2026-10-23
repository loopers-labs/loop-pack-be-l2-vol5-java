package com.loopers.infrastructure.payment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.payment.PaymentObservation;
import com.loopers.domain.payment.PaymentObservation.UnknownCause;
import com.loopers.domain.payment.PaymentProvider;
import com.loopers.domain.payment.PaymentRequest;
import com.loopers.domain.payment.PaymentTransaction;
import org.apache.hc.client5.http.ConnectTimeoutException;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ConnectionRequestTimeoutException;
import org.springframework.http.MediaType;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.net.ConnectException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class TestPgPaymentAdapter implements PaymentProvider, AutoCloseable {

    private static final String REJECTED_BEFORE_CREATE = "TEST_PG_REJECTED_BEFORE_CREATE";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final CloseableHttpClient httpClient;
    private final RestClient client;
    private final RestClient lookupClient;
    private final String callbackUrl;

    /** 조회는 다시 물을 수 있으므로 생성보다 짧게 기다린다. 두 클라이언트는 같은 연결 풀을 쓴다. */
    TestPgPaymentAdapter(String baseUrl, String callbackUrl, Duration connectTimeout, Duration readTimeout,
                         Duration lookupReadTimeout) {
        this(baseUrl, callbackUrl, connectTimeout, readTimeout, lookupReadTimeout, HttpClients.custom());
    }

    /** HTTP 처리 규칙은 그대로 두고, 실험에서 연결 풀의 크기와 공유 여부를 정할 수 있다. */
    TestPgPaymentAdapter(String baseUrl, String callbackUrl, Duration connectTimeout, Duration readTimeout,
                         Duration lookupReadTimeout, HttpClientBuilder httpClientBuilder) {
        if (!isPositive(connectTimeout) || !isPositive(readTimeout) || !isPositive(lookupReadTimeout)) {
            throw new IllegalArgumentException("PG timeout은 1ms 이상이어야 합니다.");
        }
        this.callbackUrl = callbackUrl;
        httpClient = httpClientBuilder.disableAutomaticRetries().disableRedirectHandling().build();
        client = restClient(baseUrl, connectTimeout, readTimeout);
        lookupClient = restClient(baseUrl, connectTimeout, lookupReadTimeout);
    }

    private RestClient restClient(String baseUrl, Duration connectTimeout, Duration readTimeout) {
        var factory = new HttpComponentsClientHttpRequestFactory(httpClient);
        factory.setConnectTimeout(connectTimeout);
        factory.setConnectionRequestTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory)
            .defaultHeader("X-USER-ID", "135135").build();
    }

    private static boolean isPositive(Duration timeout) {
        return timeout != null && timeout.toMillis() > 0;
    }

    @Override
    public PaymentObservation create(PaymentRequest request) {
        String providerOrderId = request.providerOrderId();
        var body = new CreateBody(providerOrderId, request.cardType(), request.cardNo(), request.amount(), callbackUrl);
        try {
            var response = client.post().uri("/api/v1/payments").contentType(MediaType.APPLICATION_JSON)
                .body(body).retrieve().toEntity(JsonNode.class);
            if (response.getStatusCode().value() != 202) {
                return PaymentObservation.unknown(UnknownCause.UNEXPECTED_RESPONSE);
            }
            // 주문 번호·금액 대조는 조회 결과와 같이 TX B가 맡아 불일치 근거를 이력에 남긴다.
            PaymentTransaction transaction = toTransaction(response.getBody());
            if (transaction.status() != PaymentTransaction.Status.PENDING) {
                return PaymentObservation.unknown(UnknownCause.UNEXPECTED_RESPONSE);
            }
            return PaymentObservation.observed(List.of(transaction));
        } catch (HttpClientErrorException.UnprocessableEntity exception) {
            boolean proven = isProvenRejection(exception.getResponseBodyAsString(), providerOrderId, request.amount());
            return proven ? PaymentObservation.rejected() : PaymentObservation.unknown(UnknownCause.UNEXPECTED_RESPONSE);
        } catch (ResourceAccessException exception) {
            return endedBeforeSending(exception)
                ? PaymentObservation.notSent() : PaymentObservation.unknown(UnknownCause.TEMPORARY_FAILURE);
        } catch (HttpServerErrorException exception) {
            return PaymentObservation.unknown(UnknownCause.TEMPORARY_FAILURE);
        } catch (RestClientException | IllegalArgumentException exception) {
            return PaymentObservation.unknown(UnknownCause.UNEXPECTED_RESPONSE);
        }
    }

    /**
     * 요청 본문이 나가기 전, 연결 단계에서 끝났는지 본다. 연결을 빌리지 못함·연결 거부·연결 시간 초과만 해당한다.
     * 응답 timeout은 요청이 PG에 닿았을 수 있어 해당하지 않는다.
     */
    private static boolean endedBeforeSending(ResourceAccessException exception) {
        for (Throwable cause = exception.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof ConnectionRequestTimeoutException || cause instanceof ConnectException
                || cause instanceof ConnectTimeoutException) {
                return true;
            }
        }
        return false;
    }

    /** 코드·거래 미생성 boolean·보낸 주문 번호·금액이 모두 맞을 때만 효과 0건의 증거로 본다. */
    private static boolean isProvenRejection(String body, String providerOrderId, long amount) {
        try {
            JsonNode node = OBJECT_MAPPER.readTree(body);
            JsonNode created = node.path("transactionCreated");
            return REJECTED_BEFORE_CREATE.equals(node.path("code").textValue())
                && created.isBoolean() && !created.booleanValue()
                && providerOrderId.equals(node.path("orderId").textValue())
                && node.path("amount").isIntegralNumber() && node.path("amount").canConvertToLong()
                && node.path("amount").longValue() == amount;
        } catch (JsonProcessingException exception) {
            return false;
        }
    }

    @Override
    public PaymentObservation findByTransactionKey(String transactionKey) {
        try {
            var response = lookupClient.get().uri("/api/v1/payments/{transactionKey}", transactionKey)
                .retrieve().toEntity(JsonNode.class);
            if (response.getStatusCode().value() != 200) {
                return PaymentObservation.unknown(UnknownCause.UNEXPECTED_RESPONSE);
            }
            return PaymentObservation.observed(List.of(toTransaction(response.getBody())));
        } catch (HttpClientErrorException.NotFound exception) {
            return PaymentObservation.observed(List.of());
        } catch (ResourceAccessException | HttpServerErrorException exception) {
            return PaymentObservation.unknown(UnknownCause.TEMPORARY_FAILURE);
        } catch (RestClientException | IllegalArgumentException exception) {
            return PaymentObservation.unknown(UnknownCause.UNEXPECTED_RESPONSE);
        }
    }

    @Override
    public PaymentObservation findByOrderId(String providerOrderId) {
        try {
            var response = lookupClient.get()
                .uri(builder -> builder.path("/api/v1/payments").queryParam("orderId", "{orderId}").build(providerOrderId))
                .retrieve().toEntity(JsonNode.class);
            JsonNode body = response.getBody();
            if (response.getStatusCode().value() != 200 || body == null || !body.isArray()) {
                return PaymentObservation.unknown(UnknownCause.UNEXPECTED_RESPONSE);
            }
            List<PaymentTransaction> transactions = new ArrayList<>();
            for (JsonNode transaction : body) {
                transactions.add(toTransaction(transaction));
            }
            return PaymentObservation.observed(transactions);
        } catch (ResourceAccessException | HttpServerErrorException exception) {
            return PaymentObservation.unknown(UnknownCause.TEMPORARY_FAILURE);
        } catch (RestClientException | IllegalArgumentException exception) {
            return PaymentObservation.unknown(UnknownCause.UNEXPECTED_RESPONSE);
        }
    }

    private static PaymentTransaction toTransaction(JsonNode body) {
        if (body == null || !body.path("transactionKey").isTextual() || !body.path("orderId").isTextual()
            || !body.path("amount").isIntegralNumber() || !body.path("amount").canConvertToLong()
            || !body.path("status").isTextual()) {
            throw new IllegalArgumentException("PG 응답 형식이 올바르지 않습니다.");
        }
        return new PaymentTransaction(body.path("transactionKey").asText(), body.path("orderId").asText(),
            body.path("amount").longValue(), PaymentTransaction.Status.valueOf(body.path("status").asText()));
    }

    @Override
    public void close() throws IOException {
        httpClient.close();
    }

    private record CreateBody(String orderId, String cardType, String cardNo, long amount, String callbackUrl) {
        @Override
        public String toString() {
            return "CreateBody[orderId=" + orderId + ", amount=" + amount + ", card=REDACTED]";
        }
    }
}
