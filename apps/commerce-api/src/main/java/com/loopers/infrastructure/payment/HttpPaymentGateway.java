package com.loopers.infrastructure.payment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.loopers.application.payment.PaymentGateway;
import com.loopers.domain.payment.PaymentStatus;

import jakarta.annotation.PreDestroy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.io.CloseMode;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;

@Component
public class HttpPaymentGateway implements PaymentGateway {
    private final ObjectMapper mapper = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final HttpClient httpClient;
    private final RestClient client;
    private final CloseableHttpClient createHttpClient;
    private final RestClient createClient;
    private final String callbackUrl;
    private final Duration readTimeout;

    public HttpPaymentGateway(
            RestClient.Builder builder,
            @Value("${payment-provider.base-url:http://127.0.0.1:18080}") URI baseUrl,
            @Value("${payment-provider.callback-url:http://127.0.0.1:8080/api/v1/payments/callback}") URI callbackUrl,
            @Value("${payment-provider.connect-timeout:500ms}") Duration connectTimeout,
            @Value("${payment-provider.read-timeout:2s}") Duration readTimeout) {
        requireLoopback(baseUrl);
        requireLoopback(callbackUrl);
        if (connectTimeout.isNegative() || connectTimeout.isZero() || readTimeout.isNegative() || readTimeout.isZero()) {
            throw new IllegalArgumentException("PG 대기 시간은 양수여야 합니다.");
        }
        this.callbackUrl = callbackUrl.toString();
        this.readTimeout = readTimeout;
        httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);
        client = builder.clone().baseUrl(baseUrl.toString()).requestFactory(requestFactory).build();
        // 생성 POST는 멱등하지 않으므로 HTTP 클라이언트 자체의 재전송도 끈다.
        createHttpClient = HttpClients.custom().disableAutomaticRetries().disableRedirectHandling().build();
        var createRequestFactory = new HttpComponentsClientHttpRequestFactory(createHttpClient);
        createRequestFactory.setConnectTimeout(connectTimeout);
        createRequestFactory.setConnectionRequestTimeout(connectTimeout);
        createRequestFactory.setReadTimeout(readTimeout);
        createClient = builder.clone().baseUrl(baseUrl.toString()).requestFactory(createRequestFactory).build();
    }

    @Override
    public CreatedPayment create(String providerOrderId, long amount, String cardType, String cardNo) {
        try {
            var response = createClient.post().uri("/api/v1/payments")
                    .header("X-USER-ID", "135135")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new CreateRequest(providerOrderId, cardType, cardNo, amount, callbackUrl))
                    .retrieve().toEntity(String.class);
            ObservedPayment body = readProviderPayment(readResponse(response.getBody()));
            if (response.getStatusCode().value() != 202 || body.status() != PaymentStatus.PENDING
                    || !providerOrderId.equals(body.providerOrderId()) || amount != body.amount()) {
                throw new UnconfirmedException();
            }
            return new CreatedPayment(body.transactionKey(), body.providerOrderId(), body.amount());
        } catch (HttpClientErrorException exception) {
            if (isVerifiedRejection(exception, providerOrderId, amount)) {
                throw new RejectedBeforeCreateException(providerOrderId, amount);
            }
            throw new UnconfirmedException(exception);
        } catch (RestClientException | CancellationException exception) {
            throw new UnconfirmedException(exception);
        }
    }

    private boolean isVerifiedRejection(HttpClientErrorException response, String providerOrderId, long amount) {
        if (response.getStatusCode().value() != 422) {
            return false;
        }
        try {
            var body = mapper.readTree(response.getResponseBodyAsString());
            return body != null && body.isObject()
                    && body.path("code").isTextual()
                    && "TEST_PG_REJECTED_BEFORE_CREATE".equals(body.path("code").textValue())
                    && body.path("orderId").isTextual()
                    && providerOrderId.equals(body.path("orderId").textValue())
                    && body.path("amount").isIntegralNumber() && body.path("amount").canConvertToLong()
                    && amount == body.path("amount").longValue()
                    && body.path("transactionCreated").isBoolean()
                    && !body.path("transactionCreated").booleanValue();
        } catch (JsonProcessingException exception) {
            return false;
        }
    }

    @PreDestroy
    public void close() {
        createHttpClient.close(CloseMode.IMMEDIATE);
        httpClient.close();
    }

    @Override
    public List<ObservedPayment> findByOrderId(String providerOrderId) {
        return findByOrderId(providerOrderId, readTimeout);
    }

    List<ObservedPayment> findByOrderId(String providerOrderId, Duration remainingBudget) {
        try {
            var response = lookupClient(remainingBudget).get()
                    .uri(builder -> builder.path("/api/v1/payments").queryParam("orderId", "{orderId}").build(providerOrderId))
                    .header("X-USER-ID", "135135")
                    .retrieve().toEntity(String.class);
            JsonNode body = readResponse(response.getBody());
            if (response.getStatusCode().value() != 200 || !body.isArray()) {
                throw new UnconfirmedException();
            }
            var payments = new ArrayList<ObservedPayment>();
            for (JsonNode paymentNode : body) {
                ObservedPayment payment = readProviderPayment(paymentNode);
                if (!providerOrderId.equals(payment.providerOrderId())) {
                    throw new UnconfirmedException();
                }
                payments.add(payment);
            }
            return List.copyOf(payments);
        } catch (RestClientException | CancellationException exception) {
            throw unconfirmedLookupFailure(exception);
        }
    }

    @Override
    public ObservedPayment findByTransactionKey(String transactionKey) {
        return findByTransactionKey(transactionKey, readTimeout);
    }

    ObservedPayment findByTransactionKey(String transactionKey, Duration remainingBudget) {
        try {
            var response = lookupClient(remainingBudget).get()
                    .uri(builder -> builder.path("/api/v1/payments").pathSegment("{transactionKey}").build(transactionKey))
                    .header("X-USER-ID", "135135")
                    .retrieve().toEntity(String.class);
            ObservedPayment body = readProviderPayment(readResponse(response.getBody()));
            if (response.getStatusCode().value() != 200 || !transactionKey.equals(body.transactionKey())) {
                throw new UnconfirmedException();
            }
            return body;
        } catch (RestClientException | CancellationException exception) {
            throw unconfirmedLookupFailure(exception);
        }
    }

    private static UnconfirmedException unconfirmedLookupFailure(RuntimeException exception) {
        // Spring은 본문 수신 중 I/O 오류를 일반 RestClientException으로 감쌀 수 있다.
        if (exception instanceof RestClientException && !(exception instanceof RestClientResponseException)
                && !(exception instanceof ResourceAccessException)) {
            for (Throwable cause = exception.getCause(); cause != null; cause = cause.getCause()) {
                if (cause instanceof IOException communicationFailure) {
                    return new UnconfirmedException(
                            new ResourceAccessException("PG 조회 응답을 읽지 못했습니다.", communicationFailure));
                }
            }
        }
        return new UnconfirmedException(exception);
    }

    private RestClient lookupClient(Duration remainingBudget) {
        Duration effectiveTimeout = remainingBudget.compareTo(readTimeout) < 0 ? remainingBudget : readTimeout;
        if (effectiveTimeout.equals(readTimeout)) {
            return client;
        }
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(effectiveTimeout);
        return client.mutate().requestFactory(requestFactory).build();
    }

    private JsonNode readResponse(String body) {
        if (body == null || body.isBlank()) {
            throw new UnconfirmedException();
        }
        try {
            return mapper.readTree(body);
        } catch (JsonProcessingException exception) {
            throw new UnconfirmedException(exception);
        }
    }

    private ObservedPayment readProviderPayment(JsonNode payment) {
        if (payment == null || !payment.isObject()
                || !payment.path("transactionKey").isTextual() || payment.path("transactionKey").textValue().isBlank()
                || !payment.path("orderId").isTextual() || payment.path("orderId").textValue().isBlank()
                || !payment.path("amount").isIntegralNumber() || !payment.path("amount").canConvertToLong()
                || payment.path("amount").longValue() <= 0
                || !payment.path("status").isTextual()
                || !Set.of("PENDING", "SUCCESS", "FAILED").contains(payment.path("status").textValue())) {
            throw new UnconfirmedException();
        }
        return new ObservedPayment(payment.path("transactionKey").textValue(), payment.path("orderId").textValue(),
                payment.path("amount").longValue(), PaymentStatus.valueOf(payment.path("status").textValue()));
    }

    private static void requireLoopback(URI uri) {
        if (!"http".equals(uri.getScheme()) || uri.getHost() == null
                || !Set.of("127.0.0.1", "localhost", "[::1]", "::1").contains(uri.getHost())) {
            throw new IllegalArgumentException("PG와 callback 주소는 로컬 HTTP 주소여야 합니다.");
        }
    }

    private record CreateRequest(String orderId, String cardType, String cardNo, long amount, String callbackUrl) {
        @Override
        public String toString() {
            return "PgCreateRequest[orderId=" + orderId + ", amount=" + amount + "]";
        }
    }

}
