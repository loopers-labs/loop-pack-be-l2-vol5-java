package com.loopers.order.infrastructure.payment.toss;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.order.domain.PaymentGateway;
import com.loopers.order.domain.PaymentGateway.CancellationConfirmed;
import com.loopers.order.domain.PaymentGateway.CancellationRejected;
import com.loopers.order.domain.PaymentGateway.CancellationRequest;
import com.loopers.order.domain.PaymentGateway.CancellationResult;
import com.loopers.order.domain.PaymentGateway.CancellationUnknown;
import com.loopers.order.domain.PaymentGateway.DispatchRejected;
import com.loopers.order.domain.PaymentGateway.ProviderError;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;

public final class TossPaymentAdapter implements PaymentGateway {

    private static final Logger LOGGER = LoggerFactory.getLogger(TossPaymentAdapter.class);

    private final RestClient client;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CircuitBreaker approvalCircuitBreaker;
    private final CircuitBreaker lookupCircuitBreaker;
    private final CircuitBreaker cancellationCircuitBreaker;
    private final Bulkhead pgRequestBulkhead;

    public TossPaymentAdapter(RestClient client) {
        this(client, CircuitBreaker.ofDefaults("toss-payment-approval"),
            CircuitBreaker.ofDefaults("toss-payment-lookup"),
            CircuitBreaker.ofDefaults("toss-payment-cancellation"), Bulkhead.ofDefaults("toss-pg-request"));
    }

    public TossPaymentAdapter(
        RestClient client,
        CircuitBreaker approvalCircuitBreaker,
        CircuitBreaker lookupCircuitBreaker,
        CircuitBreaker cancellationCircuitBreaker,
        Bulkhead pgRequestBulkhead
    ) {
        this.client = client;
        this.approvalCircuitBreaker = approvalCircuitBreaker;
        this.lookupCircuitBreaker = lookupCircuitBreaker;
        this.cancellationCircuitBreaker = cancellationCircuitBreaker;
        this.pgRequestBulkhead = pgRequestBulkhead;
    }

    @Override
    public ApprovalResult approve(ApprovalRequest request) {
        ApprovalResult result;
        String adapterFailure = "none";
        try {
            result = pgRequestBulkhead.executeSupplier(
                () -> approvalCircuitBreaker.executeSupplier(() -> approveOnce(request)));
        } catch (BulkheadFullException exception) {
            result = new DispatchRejected("PG 요청 처리 한도를 초과했습니다.");
            adapterFailure = "bulkhead_rejected";
        } catch (CallNotPermittedException exception) {
            result = new Unknown();
            adapterFailure = "circuit_open";
        } catch (RestClientException exception) {
            result = new Unknown();
            adapterFailure = "transport_error";
        }
        logApprovalBoundary(request, result, adapterFailure);
        return result;
    }

    @Override
    public ApprovalResult findByPaymentKey(String paymentKey) {
        try {
            return pgRequestBulkhead.executeSupplier(
                () -> lookupCircuitBreaker.executeSupplier(() -> findByPaymentKeyOnce(paymentKey)));
        } catch (BulkheadFullException exception) {
            return new Unknown();
        } catch (CallNotPermittedException exception) {
            return new Unknown();
        } catch (RestClientException exception) {
            return new Unknown();
        }
    }

    private ApprovalResult approveOnce(ApprovalRequest request) {
        return client.post()
            .uri("/v1/payments/confirm")
            .header("Idempotency-Key", request.idempotencyKey())
            .contentType(MediaType.APPLICATION_JSON)
            .body(Map.of(
                "paymentKey", request.paymentKey(),
                "orderId", request.providerOrderId(),
                "amount", request.amount()
            ))
            .exchange((httpRequest, response) -> {
                int status = response.getStatusCode().value();
                if (response.getStatusCode().is5xxServerError()) {
                    return new Unknown();
                }
                JsonNode body;
                try {
                    body = objectMapper.readTree(response.getBody());
                } catch (IOException exception) {
                    return new Unknown();
                }
                return status == 200 ? approved(body) : providerError(status, body);
            });
    }

    private void logApprovalBoundary(
        ApprovalRequest request,
        ApprovalResult result,
        String adapterFailure
    ) {
        String pgOutcome = result instanceof PaymentGateway.Approved ? "success"
            : result instanceof ProviderError ? "failed"
            : result instanceof DispatchRejected ? "rejected"
            : "unknown";
        Instant observedAt = Instant.now();
        LOGGER.atInfo()
            .addKeyValue("providerOrderId", request.providerOrderId())
            .addKeyValue("paymentAttemptKey", request.idempotencyKey())
            .addKeyValue("pgOperation", "approve")
            .addKeyValue("pgOutcome", pgOutcome)
            .addKeyValue("adapterFailure", adapterFailure)
            .addKeyValue("observedAt", observedAt.toString())
            .log("PG approval boundary: providerOrderId={} paymentAttemptKey={} pgOperation={} "
                    + "pgOutcome={} adapterFailure={} observedAt={}",
                request.providerOrderId(), request.idempotencyKey(), "approve", pgOutcome,
                adapterFailure, observedAt);
    }

    private ApprovalResult findByPaymentKeyOnce(String paymentKey) {
        return client.get()
            .uri("/v1/payments/{paymentKey}", paymentKey)
            .exchange((httpRequest, response) -> {
                int status = response.getStatusCode().value();
                if (response.getStatusCode().is5xxServerError()) {
                    return new Unknown();
                }
                JsonNode body;
                try {
                    body = objectMapper.readTree(response.getBody());
                } catch (IOException exception) {
                    return new Unknown();
                }
                if (status == 404) {
                    return providerError(status, body);
                }
                if (status != 200) {
                    return providerError(status, body);
                }
                String paymentStatus = text(body, "status");
                if ("DONE".equals(paymentStatus)) {
                    return approved(body);
                }
                if ("CANCELED".equals(paymentStatus) || "PARTIAL_CANCELED".equals(paymentStatus)
                    || "ABORTED".equals(paymentStatus) || "EXPIRED".equals(paymentStatus)) {
                    return new ProviderError(status, paymentStatus, paymentStatus, body.toString());
                }
                return new Unknown(body.toString());
            });
    }

    @Override
    public CancellationResult cancel(CancellationRequest request) {
        try {
            return pgRequestBulkhead.executeSupplier(
                () -> cancellationCircuitBreaker.executeSupplier(() -> cancelOnce(request)));
        } catch (BulkheadFullException | CallNotPermittedException exception) {
            return new CancellationUnknown();
        } catch (RestClientException exception) {
            return new CancellationUnknown();
        }
    }

    private CancellationResult cancelOnce(CancellationRequest request) {
        return client.post()
            .uri("/v1/payments/{paymentKey}/cancel", request.paymentKey())
            .header("Idempotency-Key", request.idempotencyKey())
            .contentType(MediaType.APPLICATION_JSON)
            .body(Map.of("cancelReason", request.cancelReason()))
            .exchange((httpRequest, response) -> {
                int status = response.getStatusCode().value();
                JsonNode body;
                try {
                    body = objectMapper.readTree(response.getBody());
                } catch (IOException exception) {
                    return new CancellationUnknown();
                }
                if (status == 200 && "CANCELED".equals(text(body, "status"))) {
                    return new CancellationConfirmed(request.paymentKey(), body.toString());
                }
                if (response.getStatusCode().is5xxServerError()) {
                    return new CancellationUnknown(body.toString());
                }
                if (response.getStatusCode().is4xxClientError()) {
                    return new CancellationRejected(status, text(body, "code"), text(body, "message"),
                        body.toString());
                }
                return new CancellationUnknown(body.toString());
            });
    }

    private ApprovalResult approved(JsonNode body) {
        return new Approved(text(body, "paymentKey"), text(body, "orderId"),
            body.path("totalAmount").longValue(), OffsetDateTime.parse(text(body, "approvedAt")));
    }

    private ApprovalResult providerError(int status, JsonNode body) {
        return new ProviderError(status, body == null ? null : text(body, "code"),
            body == null ? null : text(body, "message"));
    }

    private String text(JsonNode body, String field) {
        JsonNode value = body.path(field);
        return value.isTextual() ? value.textValue() : null;
    }
}
