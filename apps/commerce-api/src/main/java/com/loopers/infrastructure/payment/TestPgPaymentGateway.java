package com.loopers.infrastructure.payment;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.loopers.application.payment.PaymentCreateCommand;
import com.loopers.application.payment.PaymentCreateObservation;
import com.loopers.application.payment.PaymentGateway;
import com.loopers.application.payment.PaymentLookupObservation;
import com.loopers.application.payment.PgTransaction;
import com.loopers.application.payment.PgTransactionStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;

// 테스트 PG의 HTTP 응답을 관찰 결과로 판정한다. 저장된 시도와의 비교는 결과 반영 서비스가 맡는다.
// 응답 timeout에 따른 Future 직접 취소도 최종 외부 결과를 확인하지 못한 관찰로 취급한다.
// JDK HttpClient는 POST를 자동 재시도하지 않으므로 생성 요청은 호출당 한 번만 나간다.
@Component
public class TestPgPaymentGateway implements PaymentGateway {
    private static final String PROVIDER_HEADER = "X-USER-ID";
    private static final String REJECTED_CODE = "TEST_PG_REJECTED_BEFORE_CREATE";

    private final RestClient restClient;
    private final PgProperties properties;
    private final ObjectReader responseReader;

    public TestPgPaymentGateway(PgProperties properties, ObjectMapper objectMapper) {
        HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(properties.connectTimeout())
            .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        this.restClient = RestClient.builder()
            .baseUrl(properties.baseUrl())
            .requestFactory(requestFactory)
            .defaultHeader(PROVIDER_HEADER, properties.providerAccountId())
            .messageConverters(converters -> converters.replaceAll(converter ->
                converter instanceof MappingJackson2HttpMessageConverter
                    ? new MappingJackson2HttpMessageConverter(objectMapper)
                    : converter))
            .build();
        this.properties = properties;
        this.responseReader = objectMapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    @Override
    public PaymentCreateObservation create(PaymentCreateCommand command) {
        try {
            return restClient.post()
                .uri("/api/v1/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new PgCreateRequest(command.providerOrderId(), command.cardType(), command.cardNo(),
                    command.amount(), properties.callbackUrl()))
                .exchange((request, response) ->
                    mapCreate(command, response.getStatusCode().value(), readTree(response.getBody().readAllBytes())),
                    true);
        } catch (RestClientException | CancellationException exception) {
            return new PaymentCreateObservation.Unknown(exception.getClass().getSimpleName());
        }
    }

    @Override
    public PaymentLookupObservation lookup(String transactionKey) {
        try {
            return restClient.get()
                .uri("/api/v1/payments/{transactionKey}", transactionKey)
                .exchange((request, response) -> {
                    int status = response.getStatusCode().value();
                    if (status == 404) {
                        return new PaymentLookupObservation.NotFound();
                    }
                    Optional<PgTransaction> transaction = status == 200
                        ? readTree(response.getBody().readAllBytes()).flatMap(this::transaction)
                        : Optional.empty();
                    return transaction.<PaymentLookupObservation>map(found -> new PaymentLookupObservation.Found(List.of(found)))
                        .orElseGet(() -> unavailableStatus(status));
                }, true);
        } catch (RestClientException | CancellationException exception) {
            return unavailableException(exception);
        }
    }

    @Override
    public PaymentLookupObservation lookupByOrder(String providerOrderId) {
        try {
            return restClient.get()
                .uri(builder -> builder.path("/api/v1/payments").queryParam("orderId", "{orderId}").build(providerOrderId))
                .exchange((request, response) -> {
                    int status = response.getStatusCode().value();
                    Optional<List<PgTransaction>> transactions = status == 200
                        ? readTree(response.getBody().readAllBytes()).flatMap(this::transactions)
                        : Optional.empty();
                    return transactions.<PaymentLookupObservation>map(PaymentLookupObservation.Found::new)
                        .orElseGet(() -> unavailableStatus(status));
                }, true);
        } catch (RestClientException | CancellationException exception) {
            return unavailableException(exception);
        }
    }

    private PaymentLookupObservation unavailableStatus(int status) {
        PaymentLookupObservation.FailureKind kind = switch (status) {
            case 502, 503, 504 -> PaymentLookupObservation.FailureKind.TEMPORARY_HTTP;
            case 200 -> PaymentLookupObservation.FailureKind.INVALID_RESPONSE;
            default -> PaymentLookupObservation.FailureKind.HTTP_ERROR;
        };
        return new PaymentLookupObservation.Unavailable("status " + status, kind);
    }

    private PaymentLookupObservation unavailableException(RuntimeException exception) {
        PaymentLookupObservation.FailureKind kind;
        if (Thread.currentThread().isInterrupted() || hasCause(exception, InterruptedException.class)) {
            kind = PaymentLookupObservation.FailureKind.CANCELLED;
        } else if (exception instanceof CancellationException || hasCause(exception, HttpTimeoutException.class)
            || hasCause(exception, SocketTimeoutException.class)) {
            kind = PaymentLookupObservation.FailureKind.TIMEOUT;
        } else if (exception instanceof ResourceAccessException) {
            kind = PaymentLookupObservation.FailureKind.NETWORK;
        } else {
            kind = PaymentLookupObservation.FailureKind.OTHER;
        }
        return new PaymentLookupObservation.Unavailable(exception.getClass().getSimpleName(), kind);
    }

    private boolean hasCause(Throwable exception, Class<? extends Throwable> type) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) {
                return true;
            }
        }
        return false;
    }

    private PaymentCreateObservation mapCreate(PaymentCreateCommand command, int status, Optional<JsonNode> body) {
        if (body.isEmpty()) {
            return new PaymentCreateObservation.Unknown("status " + status + " without readable body");
        }
        JsonNode json = body.get();
        if (status == 202 && matchesRequest(json, command) && isText(json.get("transactionKey"))
            && "PENDING".equals(textOrNull(json.get("status")))) {
            return new PaymentCreateObservation.Accepted(json.get("transactionKey").asText());
        }
        if (status == 422 && REJECTED_CODE.equals(textOrNull(json.get("code"))) && matchesRequest(json, command)
            && json.path("transactionCreated").isBoolean() && !json.get("transactionCreated").booleanValue()) {
            return new PaymentCreateObservation.RejectedNoEffect();
        }
        return new PaymentCreateObservation.Unknown("status " + status + " outside contract");
    }

    private boolean matchesRequest(JsonNode json, PaymentCreateCommand command) {
        return command.providerOrderId().equals(textOrNull(json.get("orderId")))
            && amount(json.get("amount")).filter(amount -> amount == command.amount()).isPresent();
    }

    private Optional<List<PgTransaction>> transactions(JsonNode json) {
        if (!json.isArray()) {
            return Optional.empty();
        }
        List<PgTransaction> transactions = new ArrayList<>();
        for (JsonNode element : json) {
            Optional<PgTransaction> transaction = transaction(element);
            if (transaction.isEmpty()) {
                return Optional.empty();
            }
            transactions.add(transaction.get());
        }
        return Optional.of(transactions);
    }

    private Optional<PgTransaction> transaction(JsonNode json) {
        Optional<PgTransactionStatus> status = transactionStatus(json.get("status"));
        Optional<Long> amount = amount(json.get("amount"));
        if (!isText(json.get("transactionKey")) || !isText(json.get("orderId")) || amount.isEmpty() || status.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new PgTransaction(json.get("transactionKey").asText(), json.get("orderId").asText(),
            amount.get(), status.get()));
    }

    private Optional<PgTransactionStatus> transactionStatus(JsonNode node) {
        String status = textOrNull(node);
        for (PgTransactionStatus candidate : PgTransactionStatus.values()) {
            if (candidate.name().equals(status)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    // 금액은 JSON 정수만 인정한다. 문자열·소수는 계약 위반이다.
    private Optional<Long> amount(JsonNode node) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToLong()) {
            return Optional.empty();
        }
        return Optional.of(node.longValue());
    }

    private Optional<JsonNode> readTree(byte[] body) {
        if (body.length == 0) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(responseReader.readTree(body));
        } catch (IOException exception) {
            return Optional.empty();
        }
    }

    private static boolean isText(JsonNode node) {
        return node != null && node.isTextual() && !node.asText().isBlank();
    }

    private static String textOrNull(JsonNode node) {
        return node != null && node.isTextual() ? node.asText() : null;
    }
}
