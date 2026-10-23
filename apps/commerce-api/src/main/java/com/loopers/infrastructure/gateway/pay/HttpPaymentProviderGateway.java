package com.loopers.infrastructure.gateway.pay;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.loopers.application.pay.gateway.PaymentProviderGateway;
import com.loopers.domain.pay.model.PaymentObservation;
import com.loopers.domain.pay.model.PaymentScenario;
import com.loopers.domain.pay.model.ProviderPaymentStatus;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class HttpPaymentProviderGateway implements PaymentProviderGateway {
    private enum Operation { CREATE, LOOKUP_ONE, LOOKUP_ORDER }

    private final RestClient client;
    private final ObjectReader reader;
    private final URI callbackUri;

    public HttpPaymentProviderGateway(@Qualifier("paymentProviderRestClient") RestClient client,
                                      ObjectMapper mapper, @Qualifier("paymentCallbackUri") URI callbackUri) {
        this.client = client;
        this.reader = mapper.readerFor(JsonNode.class).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.callbackUri = callbackUri;
    }

    @Override
    public PaymentObservation create(ExpectedPayment expected, CardInput card, PaymentScenario scenario) {
        if (expected.transactionKey() != null) {
            return PaymentObservation.conflict(PaymentObservation.Reason.IDENTITY_MISMATCH);
        }
        // 저장한 실효값 9개를 모두 넘긴다. PG 프리셋 기본값으로 조건이 바뀌지 않게 한다.
        CreateBody body = new CreateBody(expected.providerOrderId(), card.cardType(), card.cardNo(), expected.amount(),
                callbackUri.toString(), Map.of("preset", "NORMAL_SUCCESS", "overrides", scenario));
        return call(() -> client.post().uri("/api/v1/payments").header("X-USER-ID", "135135").body(body)
                .exchange((request, response) -> read(response, expected, Operation.CREATE)));
    }

    @Override
    public PaymentObservation lookup(ExpectedPayment expected) {
        if (expected.transactionKey() != null) {
            return call(() -> client.get().uri(builder -> builder.path("/api/v1/payments/{key}")
                            .build(expected.transactionKey())).header("X-USER-ID", "135135")
                    .exchange((request, response) -> read(response, expected, Operation.LOOKUP_ONE)));
        }
        return call(() -> client.get().uri(builder -> builder.path("/api/v1/payments")
                        .queryParam("orderId", "{orderId}").build(expected.providerOrderId())).header("X-USER-ID", "135135")
                .exchange((request, response) -> read(response, expected, Operation.LOOKUP_ORDER)));
    }

    private PaymentObservation read(ClientHttpResponse response, ExpectedPayment expected, Operation operation)
            throws IOException {
        int status = response.getStatusCode().value();
        if (operation == Operation.LOOKUP_ONE && status == 404) {
            return PaymentObservation.missing();
        }
        if (operation == Operation.CREATE && status == 422) {
            JsonNode body = reader.readValue(response.getBody());
            if (body == null || !body.isObject() || !"TEST_PG_REJECTED_BEFORE_CREATE".equals(text(body, "code"))
                    || body.get("transactionCreated") == null || !body.get("transactionCreated").isBoolean()
                    || body.get("transactionCreated").booleanValue() || !validIdentityShape(body)) {
                return invalid();
            }
            return matchesIdentity(body, expected) ? PaymentObservation.rejected(expected.providerOrderId(), expected.amount())
                    : PaymentObservation.conflict(PaymentObservation.Reason.IDENTITY_MISMATCH);
        }
        if (status != (operation == Operation.CREATE ? 202 : 200)) {
            return PaymentObservation.httpFailure(status);
        }
        JsonNode body = reader.readValue(response.getBody());
        if (operation == Operation.LOOKUP_ORDER) {
            if (body == null || !body.isArray()) {
                return invalid();
            }
            if (body.isEmpty()) {
                return PaymentObservation.missing();
            }
            if (body.size() != 1) {
                return PaymentObservation.conflict(PaymentObservation.Reason.MULTIPLE_TRANSACTIONS);
            }
            body = body.get(0);
        }
        return payment(body, expected, operation);
    }

    private PaymentObservation payment(JsonNode body, ExpectedPayment expected, Operation operation) {
        if (body == null || !body.isObject() || !validIdentityShape(body)) {
            return invalid();
        }
        String key = text(body, "transactionKey");
        String status = text(body, "status");
        if (key == null || key.isBlank() || key.length() > 64 || status == null) {
            return invalid();
        }
        ProviderPaymentStatus result;
        try {
            result = ProviderPaymentStatus.valueOf(status);
        } catch (IllegalArgumentException exception) {
            return invalid();
        }
        if (operation == Operation.CREATE && result != ProviderPaymentStatus.PENDING) {
            return invalid();
        }
        if (!matchesIdentity(body, expected) || (expected.transactionKey() != null && !expected.transactionKey().equals(key))) {
            return PaymentObservation.conflict(PaymentObservation.Reason.IDENTITY_MISMATCH);
        }
        return PaymentObservation.verified(key, expected.providerOrderId(), expected.amount(), result);
    }

    private boolean validIdentityShape(JsonNode body) {
        String order = text(body, "orderId");
        JsonNode amount = body.get("amount");
        return order != null && !order.isBlank() && order.length() >= 6 && order.length() <= 100
                && amount != null && amount.isIntegralNumber() && amount.canConvertToLong() && amount.longValue() >= 0;
    }

    private boolean matchesIdentity(JsonNode body, ExpectedPayment expected) {
        return expected.providerOrderId().equals(text(body, "orderId")) && expected.amount() == body.get("amount").longValue();
    }

    private String text(JsonNode body, String field) {
        JsonNode value = body.get(field);
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    private PaymentObservation invalid() {
        return PaymentObservation.unavailable(PaymentObservation.Reason.INVALID_RESPONSE);
    }

    private PaymentObservation call(Supplier<PaymentObservation> request) {
        try {
            return request.get();
        } catch (RestClientException exception) {
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                if (cause instanceof SocketTimeoutException || cause instanceof java.util.concurrent.TimeoutException) {
                    return PaymentObservation.unavailable(PaymentObservation.Reason.TIMEOUT);
                }
                if (cause instanceof JsonProcessingException) {
                    return invalid();
                }
            }
            return PaymentObservation.unavailable(PaymentObservation.Reason.TRANSPORT_ERROR);
        }
    }

    private record CreateBody(String orderId, String cardType, String cardNo, long amount, String callbackUrl,
                              Map<String, Object> testOptions) {
        @Override
        public String toString() {
            return "CreateBody[redacted]";
        }
    }
}
