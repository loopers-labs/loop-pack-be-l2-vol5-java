package com.loopers.infrastructure.payment;

import com.loopers.domain.common.Money;
import com.loopers.domain.payment.CreateObservation;
import com.loopers.domain.payment.LookupResult;
import com.loopers.domain.payment.PaymentCreation;
import com.loopers.domain.payment.PaymentGateway;
import com.loopers.domain.payment.PgTransaction;
import com.loopers.domain.payment.ProviderOrderId;
import com.loopers.domain.payment.TransactionKey;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.apache.hc.core5.http.ConnectionRequestTimeoutException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

@Component
class PgPaymentGateway implements PaymentGateway {

    private static final String PAYMENTS = "/api/v1/payments";
    private static final String REJECTED_BEFORE_CREATE = "TEST_PG_REJECTED_BEFORE_CREATE";

    private final RestClient createClient;
    private final RestClient lookupClient;
    private final PgProperties pg;
    private final CircuitBreaker lookupCircuit;

    PgPaymentGateway(
        @Qualifier("pgCreateClient") RestClient createClient,
        @Qualifier("pgLookupClient") RestClient lookupClient,
        PgProperties pg,
        CircuitBreaker pgLookupCircuitBreaker
    ) {
        this.createClient = createClient;
        this.lookupClient = lookupClient;
        this.pg = pg;
        this.lookupCircuit = pgLookupCircuitBreaker;
    }

    @Override
    public CreateObservation create(PaymentCreation creation) {
        try {
            return createClient.post()
                .uri(PAYMENTS)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new CreateRequest(
                    creation.providerOrderId().value(),
                    creation.cardType().name(),
                    creation.cardNumber().value(),
                    creation.amount().amount(),
                    pg.callbackUrl()
                ))
                .exchange((request, response) -> observe(creation, response));
        } catch (ResourceAccessException e) {
            if (e.getCause() instanceof ConnectionRequestTimeoutException) {
                return new CreateObservation.NotSent();
            }
            return new CreateObservation.CreateUnknown();
        }
    }

    @Override
    public LookupResult lookup(TransactionKey transactionKey) {
        return guarded(() -> lookupByKey(transactionKey));
    }

    @Override
    public LookupResult lookup(ProviderOrderId providerOrderId) {
        return guarded(() -> lookupByOrder(providerOrderId));
    }

    private LookupResult guarded(Supplier<LookupResult> lookup) {
        try {
            return lookupCircuit.executeSupplier(lookup);
        } catch (CallNotPermittedException e) {
            return new LookupResult.Unavailable();
        }
    }

    private LookupResult lookupByKey(TransactionKey transactionKey) {
        try {
            return lookupClient.get()
                .uri(PAYMENTS + "/{transactionKey}", transactionKey.value())
                .exchange((request, response) -> {
                    if (response.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND)) {
                        return new LookupResult.NotFound();
                    }
                    if (!response.getStatusCode().isSameCodeAs(HttpStatus.OK)) {
                        return new LookupResult.Unavailable();
                    }
                    TransactionResponse body = response.bodyTo(TransactionResponse.class);
                    if (body == null) {
                        return new LookupResult.Unavailable();
                    }
                    return found(List.of(body));
                });
        } catch (RestClientException | HttpMessageNotReadableException e) {
            return new LookupResult.Unavailable();
        }
    }

    private LookupResult lookupByOrder(ProviderOrderId providerOrderId) {
        try {
            return lookupClient.get()
                .uri(PAYMENTS + "?orderId={orderId}", providerOrderId.value())
                .exchange((request, response) -> {
                    if (!response.getStatusCode().isSameCodeAs(HttpStatus.OK)) {
                        return new LookupResult.Unavailable();
                    }
                    TransactionResponse[] body = response.bodyTo(TransactionResponse[].class);
                    if (body == null) {
                        return new LookupResult.Unavailable();
                    }
                    if (body.length == 0) {
                        return new LookupResult.NotFound();
                    }
                    return found(Arrays.asList(body));
                });
        } catch (RestClientException | HttpMessageNotReadableException e) {
            return new LookupResult.Unavailable();
        }
    }

    private static LookupResult found(List<TransactionResponse> responses) {
        try {
            return new LookupResult.Found(responses.stream().map(PgPaymentGateway::toTransaction).toList());
        } catch (IllegalArgumentException e) {
            return new LookupResult.Unavailable();
        }
    }

    private static PgTransaction toTransaction(TransactionResponse response) {
        if (response == null || response.amount() == null || response.status() == null) {
            throw new IllegalArgumentException("PG 거래 응답에 빈 값이 있습니다");
        }
        return new PgTransaction(
            new TransactionKey(response.transactionKey()),
            new ProviderOrderId(response.orderId()),
            Money.of(response.amount()),
            PgTransaction.Status.valueOf(response.status())
        );
    }

    private static CreateObservation observe(PaymentCreation creation, ConvertibleClientHttpResponse response)
        throws IOException {
        try {
            if (response.getStatusCode().isSameCodeAs(HttpStatus.ACCEPTED)) {
                CreateResponse accepted = response.bodyTo(CreateResponse.class);
                if (isAcceptedAsRequested(creation, accepted)) {
                    return new CreateObservation.Accepted(new TransactionKey(accepted.transactionKey()));
                }
                return new CreateObservation.CreateUnknown();
            }
            if (response.getStatusCode().isSameCodeAs(HttpStatus.UNPROCESSABLE_ENTITY)
                && isRejectedWithoutEffect(creation, response.bodyTo(RejectionResponse.class))) {
                return new CreateObservation.RejectedNoEffect();
            }
        } catch (RestClientException | HttpMessageNotReadableException e) {
            return new CreateObservation.CreateUnknown();
        }
        return new CreateObservation.CreateUnknown();
    }

    private static boolean isAcceptedAsRequested(PaymentCreation creation, CreateResponse accepted) {
        return accepted != null
            && accepted.transactionKey() != null
            && !accepted.transactionKey().isBlank()
            && creation.providerOrderId().value().equals(accepted.orderId())
            && Long.valueOf(creation.amount().amount()).equals(accepted.amount());
    }

    private static boolean isRejectedWithoutEffect(PaymentCreation creation, RejectionResponse rejection) {
        return rejection != null
            && REJECTED_BEFORE_CREATE.equals(rejection.code())
            && Boolean.FALSE.equals(rejection.transactionCreated())
            && creation.providerOrderId().value().equals(rejection.orderId())
            && Long.valueOf(creation.amount().amount()).equals(rejection.amount());
    }

    record CreateRequest(String orderId, String cardType, String cardNo, long amount, String callbackUrl) {
    }

    record CreateResponse(String transactionKey, String orderId, Long amount, String status) {
    }

    record TransactionResponse(String transactionKey, String orderId, Long amount, String status) {
    }

    record RejectionResponse(String code, Boolean transactionCreated, String orderId, Long amount) {
    }
}
