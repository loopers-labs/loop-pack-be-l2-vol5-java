package com.loopers.order.infrastructure.payment.toss;

import com.loopers.order.domain.PaymentGateway;
import com.loopers.order.domain.PaymentGateway.ApprovalRequest;
import com.loopers.order.domain.PaymentGateway.Approved;
import com.loopers.order.domain.PaymentGateway.CancellationConfirmed;
import com.loopers.order.domain.PaymentGateway.CancellationRequest;
import com.loopers.order.domain.PaymentGateway.ProviderError;
import com.loopers.order.domain.PaymentGateway.Unknown;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("토스 PG 승인 어댑터")
class TossPaymentAdapterTest {

    private static final ApprovalRequest REQUEST =
        new ApprovalRequest("test-payment-42", "LP-ORD-0000000042", 7000, "test-attempt-42");
    private static final OffsetDateTime APPROVED_AT =
        OffsetDateTime.parse("2026-10-21T15:00:00+09:00");

    private TossPgFixture pg;
    private PaymentGateway gateway;

    @BeforeEach
    void setUp() {
        pg = new TossPgFixture();
        gateway = new TossPaymentAdapter(TossHttpClientFactory.create(
            pg.baseUrl(), TossPgFixture.SECRET_KEY, Duration.ofSeconds(1), Duration.ofSeconds(1)));
    }

    @AfterEach
    void tearDown() {
        pg.close();
    }

    @Test
    @DisplayName("승인 요청을 보내고 200 DONE 응답을 내부 승인 성공 결과로 변환한다")
    void convertsApprovedPayment() {
        pg.approve(REQUEST, APPROVED_AT);

        var result = gateway.approve(REQUEST);

        assertThat(result).isEqualTo(new Approved(
            REQUEST.paymentKey(), REQUEST.providerOrderId(), REQUEST.amount(), APPROVED_AT));
    }

    @Test
    @DisplayName("paymentKey로 조회한 200 DONE 응답을 내부 승인 성공 결과로 변환한다")
    void convertsPaymentLookupResult() {
        pg.payment(REQUEST.paymentKey(), 200, """
            {
              "paymentKey": "test-payment-42",
              "orderId": "LP-ORD-0000000042",
              "totalAmount": 7000,
              "status": "DONE",
              "approvedAt": "2026-10-21T15:00:00+09:00"
            }
            """);

        var result = gateway.findByPaymentKey(REQUEST.paymentKey());

        assertThat(result).isEqualTo(new Approved(
            REQUEST.paymentKey(), REQUEST.providerOrderId(), REQUEST.amount(), APPROVED_AT));
        assertThat(pg.paymentRequests(REQUEST.paymentKey())).hasSize(1);
        assertThat(pg.unmatchedRequests()).isEmpty();
    }

    @Test
    @DisplayName("paymentKey 조회가 404이면 결제 없음 오류로 변환한다")
    void returnsProviderErrorWhenPaymentLookupIsNotFound() {
        pg.payment(REQUEST.paymentKey(), 404, "{}");

        assertThat(gateway.findByPaymentKey(REQUEST.paymentKey())).isEqualTo(new ProviderError(404, null, null));
        assertThat(pg.paymentRequests(REQUEST.paymentKey())).hasSize(1);
        assertThat(pg.unmatchedRequests()).isEmpty();
    }

    @Test
    @DisplayName("paymentKey 조회가 PROCESSING이면 결과 미확정으로 변환한다")
    void returnsUnknownWhenPaymentLookupIsProcessing() {
        pg.payment(REQUEST.paymentKey(), 200, """
            {
              "paymentKey": "test-payment-42",
              "status": "IN_PROGRESS"
            }
            """);

        assertThat(gateway.findByPaymentKey(REQUEST.paymentKey()))
            .isEqualTo(new Unknown("{\"paymentKey\":\"test-payment-42\",\"status\":\"IN_PROGRESS\"}"));
        assertThat(pg.paymentRequests(REQUEST.paymentKey())).hasSize(1);
        assertThat(pg.unmatchedRequests()).isEmpty();
    }

    @Test
    @DisplayName("paymentKey로 PG 취소를 요청하고 취소 확인 응답을 변환한다")
    void convertsCanceledPayment() {
        var request = new CancellationRequest(
            REQUEST.paymentKey(), "결제 결과 미확정 주문 취소", REQUEST.idempotencyKey() + "-cancel");
        pg.cancel(request);

        var result = gateway.cancel(request);

        assertThat(result).isInstanceOf(CancellationConfirmed.class);
        assertThat(((CancellationConfirmed) result).paymentKey()).isEqualTo(REQUEST.paymentKey());
        assertThat(pg.cancellationRequests(request)).hasSize(1);
        assertThat(pg.unmatchedRequests()).isEmpty();
    }

    @Test
    @DisplayName("PG 오류의 HTTP 상태·코드·메시지를 전달한다")
    void preservesProviderError() {
        pg.error(REQUEST, "INVALID_REQUEST", "테스트 오류");

        var result = gateway.approve(REQUEST);

        assertThat(result).isEqualTo(new ProviderError(400, "INVALID_REQUEST", "테스트 오류"));
    }

    @Test
    @DisplayName("응답 대기 시간을 초과하면 Unknown을 반환하고 승인을 재시도하지 않는다")
    void returnsUnknownOnTimeoutWithoutRetry() {
        pg.delayedApproval(REQUEST, APPROVED_AT, Duration.ofSeconds(3));

        var result = gateway.approve(REQUEST);

        assertThat(result).isEqualTo(new Unknown());
        assertThat(pg.approvalRequests()).hasSize(1);
        assertThat(pg.unmatchedRequests()).isEmpty();
    }

    @ParameterizedTest(name = "HTTP {0}")
    @ValueSource(ints = {500, 503})
    @DisplayName("PG 5xx 응답은 Unknown으로 반환한다")
    void returnsUnknownForServerError(int status) {
        pg.respond(REQUEST, status, "", Duration.ZERO);

        assertThat(gateway.approve(REQUEST)).isEqualTo(new Unknown());
    }

}
