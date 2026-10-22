package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.CreateObservation;
import com.loopers.domain.payment.PaymentCreation;
import com.loopers.domain.payment.PaymentGateway;
import com.loopers.domain.payment.TransactionKey;
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

@Component
class PgPaymentGateway implements PaymentGateway {

    private static final String PAYMENTS = "/api/v1/payments";
    private static final String REJECTED_BEFORE_CREATE = "TEST_PG_REJECTED_BEFORE_CREATE";

    private final RestClient createClient;
    private final PgProperties pg;

    PgPaymentGateway(@Qualifier("pgCreateClient") RestClient createClient, PgProperties pg) {
        this.createClient = createClient;
        this.pg = pg;
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

    record RejectionResponse(String code, Boolean transactionCreated, String orderId, Long amount) {
    }
}
