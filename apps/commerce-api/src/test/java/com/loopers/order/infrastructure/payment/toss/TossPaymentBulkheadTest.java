package com.loopers.order.infrastructure.payment.toss;

import com.loopers.order.domain.PaymentGateway;
import com.loopers.order.domain.PaymentGateway.ApprovalRequest;
import com.loopers.order.domain.PaymentGateway.Approved;
import com.loopers.order.domain.PaymentGateway.CancellationRequest;
import com.loopers.order.domain.PaymentGateway.CancellationUnknown;
import com.loopers.order.domain.PaymentGateway.DispatchRejected;
import com.loopers.order.domain.PaymentGateway.Unknown;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("토스 PG 요청 Bulkhead")
class TossPaymentBulkheadTest {

    private static final ApprovalRequest APPROVAL = new ApprovalRequest(
        "test-payment-bulkhead", "LP-ORD-BULKHEAD", 7000, "test-attempt-bulkhead");
    private static final OffsetDateTime APPROVED_AT =
        OffsetDateTime.parse("2026-10-21T15:00:00+09:00");

    private TossPgFixture pg;
    private PaymentGateway gateway;
    private Bulkhead bulkhead;

    @BeforeEach
    void setUp() {
        pg = new TossPgFixture();
        bulkhead = Bulkhead.of("test-toss-pg", BulkheadConfig.custom()
            .maxConcurrentCalls(1)
            .maxWaitDuration(Duration.ZERO)
            .build());
        gateway = new TossPaymentAdapter(
            TossHttpClientFactory.create(pg.baseUrl(), TossPgFixture.SECRET_KEY,
                Duration.ofSeconds(1), Duration.ofSeconds(4)),
            CircuitBreaker.ofDefaults("test-approval"),
            CircuitBreaker.ofDefaults("test-lookup"),
            CircuitBreaker.ofDefaults("test-cancellation"),
            bulkhead);
    }

    @AfterEach
    void tearDown() {
        pg.close();
    }

    @Test
    @DisplayName("PG 요청 한도가 차면 승인·조회·취소 요청을 PG에 보내지 않고 허가를 반환한다")
    void rejectsProviderCallsAtHttpBoundaryWhenBulkheadIsFull() throws Exception {
        pg.delayedApproval(APPROVAL, APPROVED_AT, Duration.ofSeconds(2));
        CompletableFuture<PaymentGateway.ApprovalResult> inFlight =
            CompletableFuture.supplyAsync(() -> gateway.approve(APPROVAL));
        awaitApprovalRequest();

        PaymentGateway.ApprovalResult rejectedApproval = gateway.approve(new ApprovalRequest(
            "another-payment", "LP-ORD-ANOTHER", 7000, "another-attempt"));
        PaymentGateway.ApprovalResult rejectedLookup = gateway.findByPaymentKey("lookup-payment");
        CancellationRequest cancellation = new CancellationRequest(
            "cancel-payment", "결제 결과 미확인 주문 취소", "cancel-attempt");
        PaymentGateway.CancellationResult rejectedCancellation = gateway.cancel(cancellation);

        assertThat(rejectedApproval).isInstanceOf(DispatchRejected.class);
        assertThat(rejectedLookup).isInstanceOf(Unknown.class);
        assertThat(rejectedCancellation).isInstanceOf(CancellationUnknown.class);
        assertThat(pg.approvalRequests()).hasSize(1);
        assertThat(pg.paymentRequests("lookup-payment")).isEmpty();
        assertThat(pg.cancellationRequests(cancellation)).isEmpty();
        assertThat(inFlight.get(5, TimeUnit.SECONDS)).isInstanceOf(Approved.class);
        assertThat(bulkhead.tryAcquirePermission()).isTrue();
        bulkhead.releasePermission();
    }

    private void awaitApprovalRequest() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (pg.approvalRequests().isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(pg.approvalRequests()).hasSize(1);
    }
}
