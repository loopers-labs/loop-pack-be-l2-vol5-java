package com.loopers.order.infrastructure.payment.toss;

import com.loopers.order.domain.PaymentGateway;
import com.loopers.order.infrastructure.payment.toss.TossPgFixture.RecordedTransaction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("응답 유실 PG 사고 fixture")
@ExtendWith(OutputCaptureExtension.class)
class TossPgFixtureIncidentTest {

    private static final OffsetDateTime APPROVED_AT = OffsetDateTime.parse("2026-11-27T10:00:00Z");

    @Test
    @DisplayName("PG 거래를 저장하고 생성 응답을 유실한 뒤 같은 거래를 조회한다")
    void recordsSuccessBeforeDroppingApprovalResponse(CapturedOutput output) {
        String paymentKey = "incident-payment-" + UUID.randomUUID();
        var request = new PaymentGateway.ApprovalRequest(
            paymentKey, "LP-ORD-0000000042", 2_000L, "incident-attempt-" + UUID.randomUUID());

        try (TossPgFixture fixture = new TossPgFixture()) {
            fixture.setResponseDropEnabled(true, List.of(request), APPROVED_AT);
            PaymentGateway gateway = new TossPaymentAdapter(TossHttpClientFactory.create(
                fixture.baseUrl(), TossPgFixture.SECRET_KEY, Duration.ofSeconds(1), Duration.ofSeconds(1)));

            PaymentGateway.ApprovalResult approvalResult = gateway.approve(request);

            assertThat(approvalResult).isInstanceOf(PaymentGateway.Unknown.class);
            assertThat(output.getOut())
                .contains("paymentAttemptKey=" + request.idempotencyKey())
                .contains("providerOrderId=" + request.providerOrderId())
                .contains("pgOperation=approve")
                .contains("pgOutcome=unknown")
                .doesNotContain(TossPgFixture.SECRET_KEY, request.paymentKey());
            RecordedTransaction storedTransaction = fixture.transaction(paymentKey);
            assertThat(storedTransaction)
                .isEqualTo(new RecordedTransaction(paymentKey, request.providerOrderId(), request.amount(),
                    "SUCCESS", paymentKey, APPROVED_AT));
            assertThat(fixture.approvalRequestCount(paymentKey)).isEqualTo(1);
            assertThat(fixture.transactions()).containsExactly(storedTransaction);

            fixture.setResponseDropEnabled(false, List.of(request), APPROVED_AT);
            fixture.revealTransaction(paymentKey);

            PaymentGateway.ApprovalResult lookupResult = gateway.findByPaymentKey(paymentKey);
            assertThat(lookupResult).isEqualTo(new PaymentGateway.Approved(
                paymentKey, request.providerOrderId(), request.amount(), APPROVED_AT));
            assertThat(fixture.approvalRequestCount(paymentKey)).isEqualTo(1);
            assertThat(fixture.paymentRequests(paymentKey)).hasSize(1);
            assertThat(fixture.responseDropEnabled()).isFalse();
        }
    }
}
