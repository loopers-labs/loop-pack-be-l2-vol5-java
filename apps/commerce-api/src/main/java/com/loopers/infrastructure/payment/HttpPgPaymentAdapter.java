package com.loopers.infrastructure.payment;

import com.loopers.application.payment.PgPaymentPort;
import com.loopers.application.payment.PgPaymentRequest;
import com.loopers.application.payment.PgPaymentResult;
import com.loopers.application.payment.PgPaymentResult.PgStatus;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class HttpPgPaymentAdapter implements PgPaymentPort {

    private static final String HEADER_PROVIDER_ID = "X-USER-ID";
    private static final String REJECTED_CODE = "TEST_PG_REJECTED_BEFORE_CREATE";

    private final RestClient restClient;
    private final String providerId;
    private final CircuitBreaker circuitBreaker;

    public HttpPgPaymentAdapter(PgProperties pgProperties, CircuitBreakerRegistry registry) {
        this.restClient = RestClient.builder()
            .baseUrl(pgProperties.baseUrl())
            .build();
        this.providerId = pgProperties.providerId();
        this.circuitBreaker = registry.circuitBreaker("pg-query");
    }

    @Override
    public PgPaymentResult pay(PgPaymentRequest request) {
        var body = Map.of(
            "orderId", request.providerOrderId(),
            "amount", request.amount(),
            "callbackUrl", request.callbackUrl()
        );

        try {
            PgPaymentResponseDto dto = restClient.post()
                .uri("/api/v1/payments")
                .header(HEADER_PROVIDER_ID, providerId)
                .body(body)
                .retrieve()
                .body(PgPaymentResponseDto.class);

            if (dto == null) {
                return new PgPaymentResult(null, request.providerOrderId(), request.amount(), PgStatus.UNKNOWN);
            }
            return new PgPaymentResult(dto.transactionKey(), dto.orderId(), dto.amount(), mapStatus(dto.status()));

        } catch (HttpClientErrorException.UnprocessableEntity e) {
            return new PgPaymentResult(null, request.providerOrderId(), request.amount(), PgStatus.REJECTED);
        } catch (RestClientException e) {
            log.warn("PG pay 호출 실패: {}", e.getMessage());
            return new PgPaymentResult(null, request.providerOrderId(), request.amount(), PgStatus.UNKNOWN);
        }
    }

    @Override
    public PgPaymentResult getByTransactionKey(String transactionKey) {
        return circuitBreaker.executeSupplier(() -> {
            try {
                PgPaymentResponseDto dto = restClient.get()
                    .uri("/api/v1/payments/{transactionKey}", transactionKey)
                    .header(HEADER_PROVIDER_ID, providerId)
                    .retrieve()
                    .body(PgPaymentResponseDto.class);

                if (dto == null) {
                    return new PgPaymentResult(transactionKey, null, 0, PgStatus.UNKNOWN);
                }
                return new PgPaymentResult(dto.transactionKey(), dto.orderId(), dto.amount(), mapStatus(dto.status()));

            } catch (RestClientException e) {
                log.warn("PG getByTransactionKey 호출 실패: {}", e.getMessage());
                throw e;
            }
        });
    }

    @Override
    public List<PgPaymentResult> getByProviderOrderId(String providerOrderId) {
        return circuitBreaker.executeSupplier(() -> {
            try {
                PgPaymentListResponseDto response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                        .path("/api/v1/payments")
                        .queryParam("orderId", providerOrderId)
                        .build())
                    .header(HEADER_PROVIDER_ID, providerId)
                    .retrieve()
                    .body(PgPaymentListResponseDto.class);

                if (response == null || response.transactions() == null) {
                    return List.of();
                }
                return response.transactions().stream()
                    .map(dto -> new PgPaymentResult(dto.transactionKey(), dto.orderId(), dto.amount(), mapStatus(dto.status())))
                    .toList();

            } catch (RestClientException e) {
                log.warn("PG getByProviderOrderId 호출 실패: {}", e.getMessage());
                throw e;
            }
        });
    }

    private PgStatus mapStatus(String status) {
        if (status == null) return PgStatus.UNKNOWN;
        return switch (status.toUpperCase()) {
            case "SUCCESS" -> PgStatus.SUCCESS;
            case "FAILED" -> PgStatus.FAILED;
            case "PENDING" -> PgStatus.PENDING;
            default -> PgStatus.UNKNOWN;
        };
    }

    private record PgPaymentResponseDto(String transactionKey, String orderId, long amount, String status) {}
    private record PgPaymentListResponseDto(List<PgPaymentResponseDto> transactions) {}
}
