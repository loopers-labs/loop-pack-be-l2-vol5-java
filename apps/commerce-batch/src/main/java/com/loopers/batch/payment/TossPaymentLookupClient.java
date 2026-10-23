package com.loopers.batch.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

@Component
public class TossPaymentLookupClient implements PaymentLookupClient {
    private final RestClient client;
    private final ObjectMapper objectMapper;
    private final CircuitBreaker circuitBreaker;

    public TossPaymentLookupClient(
        RestClient.Builder builder,
        ObjectMapper objectMapper,
        TossPaymentProperties properties,
        CircuitBreaker paymentLookupCircuitBreaker
    ) {
        String credential = properties.secretKey() + ":";
        String authorization = Base64.getEncoder().encodeToString(credential.getBytes(StandardCharsets.UTF_8));
        this.client = builder
            .baseUrl(properties.baseUrl())
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + authorization)
            .build();
        this.objectMapper = objectMapper;
        this.circuitBreaker = paymentLookupCircuitBreaker;
    }

    @Override
    public LookupResult findByOrderId(String orderId) {
        try {
            return circuitBreaker.executeSupplier(() -> client.get()
                .uri("/v1/payments/orders/{orderId}", orderId)
                .exchange((request, response) -> {
                    int httpStatus = response.getStatusCode().value();
                    String body = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
                    if (httpStatus != 200) {
                        return new LookupResult(httpStatus, null, body);
                    }
                    JsonNode json = objectMapper.readTree(body);
                    JsonNode status = json.path("status");
                    return new LookupResult(httpStatus, status.isTextual() ? status.textValue() : null, body);
                }));
        } catch (CallNotPermittedException exception) {
            return unresolved();
        } catch (RestClientException exception) {
            return unresolved();
        }
    }

    private LookupResult unresolved() {
        return new LookupResult(0, null, null);
    }
}
