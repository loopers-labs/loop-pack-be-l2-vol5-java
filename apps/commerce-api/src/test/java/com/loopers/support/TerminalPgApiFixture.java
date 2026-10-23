package com.loopers.support;

import com.loopers.domain.payment.PaymentCreateRequest;
import com.loopers.domain.payment.PaymentGateway;
import com.loopers.domain.payment.PaymentGatewayResult;
import com.loopers.domain.payment.PaymentRequestFingerprinter;
import com.loopers.domain.payment.PaymentTransaction;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unrelated HTTP regression tests use a deterministic terminal PG port and synthetic HMAC key.
 * Actual PG HTTP and waiting behavior are covered separately by PgOrderConfirmApiE2ETest.
 */
@TestPropertySource(properties = "commerce.test-pg.enabled=true")
public abstract class TerminalPgApiFixture {
    @TestBean(methodName = "com.loopers.infrastructure.payment.support.PaymentFingerprintTestConfiguration#create")
    protected PaymentRequestFingerprinter fingerprinter;

    @TestBean(methodName = "com.loopers.support.TerminalPgApiFixture#paymentGateway")
    protected PaymentGateway paymentGateway;

    protected String pgBody() {
        return "{\"paymentAttemptKey\":\"" + UUID.randomUUID() + "\",\"cardType\":\"TEST\",\"cardNo\":\"0000-0000-0000-0000\"}";
    }

    public static PaymentGateway paymentGateway() {
        return new PaymentGateway() {
            @Override
            public PaymentGatewayResult create(PaymentCreateRequest request) {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                return PaymentGatewayResult.observed(List.of(new PaymentTransaction("135135", request.providerOrderId(),
                    "REGRESSION-" + request.providerOrderId(), request.amount(), PaymentTransaction.Status.SUCCESS)));
            }

            @Override
            public PaymentGatewayResult findByTransactionKey(String transactionKey) {
                throw new AssertionError("Immediate terminal fixture must not require another lookup");
            }

            @Override
            public PaymentGatewayResult findByProviderOrderId(String providerOrderId) {
                throw new AssertionError("Immediate terminal fixture must not require another lookup");
            }
        };
    }
}
