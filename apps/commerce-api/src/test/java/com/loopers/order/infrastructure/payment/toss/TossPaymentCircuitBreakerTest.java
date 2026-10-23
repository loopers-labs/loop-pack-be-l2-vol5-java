package com.loopers.order.infrastructure.payment.toss;

import com.loopers.order.domain.PaymentGateway;
import com.loopers.order.domain.PaymentGateway.ApprovalRequest;
import com.loopers.order.domain.PaymentGateway.Approved;
import com.loopers.order.domain.PaymentGateway.CancellationRequest;
import com.loopers.order.domain.PaymentGateway.CancellationUnknown;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("토스 PG 호출 서킷 브레이커")
class TossPaymentCircuitBreakerTest {

    private static final ApprovalRequest REQUEST =
        new ApprovalRequest("test-payment-circuit", "LP-ORD-CIRCUIT", 7000, "test-attempt-circuit");
    private static final OffsetDateTime APPROVED_AT =
        OffsetDateTime.parse("2026-10-21T15:00:00+09:00");
    private TossPgFixture pg;
    private CircuitBreaker approvalCircuitBreaker;
    private CircuitBreaker lookupCircuitBreaker;
    private CircuitBreaker cancellationCircuitBreaker;
    private PaymentGateway gateway;

    @BeforeEach
    void setUp() {
        pg = new TossPgFixture();
        approvalCircuitBreaker = testCircuitBreaker("test-approval");
        lookupCircuitBreaker = testCircuitBreaker("test-lookup");
        cancellationCircuitBreaker = testCircuitBreaker("test-cancellation");
        gateway = new TossPaymentAdapter(TossHttpClientFactory.create(
            pg.baseUrl(), TossPgFixture.SECRET_KEY, Duration.ofSeconds(1), Duration.ofSeconds(1)),
            approvalCircuitBreaker, lookupCircuitBreaker, cancellationCircuitBreaker,
            Bulkhead.ofDefaults("test-toss-pg"));
    }

    @AfterEach
    void tearDown() {
        pg.close();
    }

    @Test
    @DisplayName("기술적 5xx 실패율이 임계치에 도달하면 승인 요청을 더 보내지 않는다")
    void opensApprovalCircuitAndRejectsFurtherRequests() {
        pg.serverError(REQUEST);

        assertThat(gateway.approve(REQUEST)).isInstanceOf(PaymentGateway.Unknown.class);
        assertThat(gateway.approve(REQUEST)).isInstanceOf(PaymentGateway.Unknown.class);

        assertThat(approvalCircuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(gateway.approve(REQUEST)).isInstanceOf(PaymentGateway.Unknown.class);
        assertThat(pg.approvalRequests()).hasSize(2);
    }

    @Test
    @DisplayName("승인 응답 타임아웃은 기술 실패로 집계해 승인 서킷을 연다")
    void opensApprovalCircuitAfterRepeatedTimeouts() {
        pg.delayedApproval(REQUEST, APPROVED_AT, Duration.ofSeconds(3));

        assertThat(gateway.approve(REQUEST)).isInstanceOf(PaymentGateway.Unknown.class);
        assertThat(gateway.approve(REQUEST)).isInstanceOf(PaymentGateway.Unknown.class);

        assertThat(approvalCircuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(pg.approvalRequests()).hasSize(2);
    }

    @Test
    @DisplayName("HALF_OPEN 확인 호출이 성공하면 승인 서킷을 닫는다")
    void closesApprovalCircuitAfterSuccessfulHalfOpenProbe() {
        approvalCircuitBreaker.transitionToOpenState();
        approvalCircuitBreaker.transitionToHalfOpenState();
        pg.approve(REQUEST, APPROVED_AT);

        assertThat(gateway.approve(REQUEST)).isInstanceOf(Approved.class);

        assertThat(approvalCircuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(pg.approvalRequests()).hasSize(1);
    }

    @Test
    @DisplayName("PG의 정상적인 결제 거절 응답은 승인 서킷을 열지 않는다")
    void doesNotOpenApprovalCircuitForProviderRejections() {
        pg.error(REQUEST, "INVALID_REQUEST", "잘못된 승인 요청");

        assertThat(gateway.approve(REQUEST)).isInstanceOf(PaymentGateway.ProviderError.class);
        assertThat(gateway.approve(REQUEST)).isInstanceOf(PaymentGateway.ProviderError.class);

        assertThat(approvalCircuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(pg.approvalRequests()).hasSize(2);
    }

    @Test
    @DisplayName("정상 응답인 결제 PROCESSING 결과는 조회 서킷 실패로 집계하지 않는다")
    void doesNotOpenLookupCircuitForProcessingResult() {
        pg.payment(REQUEST.paymentKey(), 200, """
            {
              "paymentKey": "test-payment-circuit",
              "status": "IN_PROGRESS"
            }
            """);

        assertThat(gateway.findByPaymentKey(REQUEST.paymentKey())).isInstanceOf(PaymentGateway.Unknown.class);
        assertThat(gateway.findByPaymentKey(REQUEST.paymentKey())).isInstanceOf(PaymentGateway.Unknown.class);

        assertThat(lookupCircuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(pg.paymentRequests(REQUEST.paymentKey())).hasSize(2);
    }

    @Test
    @DisplayName("승인 서킷이 열려도 보상 취소 요청은 PG로 보낸다")
    void sendsCancellationEvenWhenApprovalCircuitIsOpen() {
        pg.serverError(REQUEST);
        gateway.approve(REQUEST);
        gateway.approve(REQUEST);
        CancellationRequest cancellationRequest = new CancellationRequest(
            REQUEST.paymentKey(), "결제 결과 미확인 주문 취소", REQUEST.idempotencyKey() + "-cancel");
        pg.cancel(cancellationRequest);

        assertThat(approvalCircuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(gateway.cancel(cancellationRequest)).isInstanceOf(PaymentGateway.CancellationConfirmed.class);
        assertThat(pg.cancellationRequests(cancellationRequest)).hasSize(1);
    }

    @Test
    @DisplayName("취소 POST 기술 실패율이 임계치에 도달하면 취소 요청을 더 보내지 않는다")
    void opensCancellationCircuitAndRejectsFurtherRequests() {
        CancellationRequest cancellationRequest = new CancellationRequest(
            REQUEST.paymentKey(), "결제 결과 미확인 주문 취소", REQUEST.idempotencyKey() + "-cancel");
        pg.cancellationServerError(cancellationRequest);

        assertThat(gateway.cancel(cancellationRequest)).isInstanceOf(CancellationUnknown.class);
        assertThat(gateway.cancel(cancellationRequest)).isInstanceOf(CancellationUnknown.class);

        assertThat(cancellationCircuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(gateway.cancel(cancellationRequest)).isInstanceOf(CancellationUnknown.class);
        assertThat(pg.cancellationRequests(cancellationRequest)).hasSize(2);
    }

    private CircuitBreaker testCircuitBreaker(String name) {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
            .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
            .slidingWindowSize(2)
            .minimumNumberOfCalls(2)
            .failureRateThreshold(50)
            .waitDurationInOpenState(Duration.ofMinutes(1))
            .permittedNumberOfCallsInHalfOpenState(1)
            .recordResult(result -> result instanceof PaymentGateway.CancellationUnknown
                || (result instanceof PaymentGateway.Unknown unknown
                && (unknown.rawResponse() == null || unknown.rawResponse().isBlank())))
            .build();
        return CircuitBreaker.of(name, config);
    }
}
