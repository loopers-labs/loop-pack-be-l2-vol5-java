package com.loopers.application.order;

import com.loopers.application.user.UserRegistrationService;
import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.payment.PaymentAttempt;
import com.loopers.domain.payment.PaymentAttemptStatus;
import com.loopers.domain.payment.PaymentEvent;
import com.loopers.domain.product.ProductModel;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.payment.PaymentAttemptJpaRepository;
import com.loopers.infrastructure.payment.PaymentEventRepositoryImpl;
import com.loopers.infrastructure.payment.ResilientPaymentProvider;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.support.payment.FakePaymentServer;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

/**
 * DB 상태가 같은 두 원인(생성 응답을 받지 못함 / 조회로 SUCCESS를 받았지만 TX B rollback)을 경계 로그로 구분할 수 있는지 본다.
 * 카드번호가 로그에 남지 않는지, 결과 확인을 미룬 주문이 주문 ID와 이유로 남는지도 확인한다.
 */
@SpringBootTest
@ExtendWith(OutputCaptureExtension.class)
class PaymentBoundaryLogTest {

    private static final FakePaymentServer PG = startPg();
    private static final String CARD_NO = "1234-5678-9814-1451";

    @DynamicPropertySource
    static void pgUrl(DynamicPropertyRegistry registry) {
        registry.add("commerce.payment.pg.base-url", PG::baseUrl);
    }

    @Autowired private UserRegistrationService registration;
    @Autowired private OrderFacade orderFacade;
    @Autowired private ResilientPaymentProvider resilientPaymentProvider;
    @Autowired private BrandJpaRepository brandJpaRepository;
    @Autowired private ProductJpaRepository productJpaRepository;
    @Autowired private PaymentAttemptJpaRepository paymentAttemptJpaRepository;
    @Autowired private DatabaseCleanUp databaseCleanUp;
    @MockitoSpyBean private PaymentEventRepositoryImpl paymentEventRepository;

    private Long userId;
    private Long orderId;

    @BeforeEach
    void setUp() {
        PG.reset();
        resilientPaymentProvider.lookupCircuitBreaker().reset();
        userId = registration.register().getId();
        Long brandId = brandJpaRepository.save(new BrandModel("나이키", null)).getId();
        Long productId = productJpaRepository.save(new ProductModel(brandId, "양말", 1_000L, 5)).getId();
        orderId = orderFacade.create(userId, List.of(new OrderFacade.OrderLine(productId, 3))).id();
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @AfterAll
    static void stopPg() throws InterruptedException {
        PG.close();
    }

    @Test
    void createResponseTimeoutLeavesRequestAndNoAnswerLinesWithoutCardNumber(CapturedOutput output) {
        PG.delayNextResponse(Duration.ofMillis(1_500));

        confirm();

        String providerOrderId = providerOrderId();
        assertThat(attempt().getStatus()).isEqualTo(PaymentAttemptStatus.UNKNOWN);
        assertThat(output).contains("PG 요청: op=create providerOrderId=" + providerOrderId + " amount=3000");
        assertThat(output).contains("PG 응답 없음: op=create providerOrderId=" + providerOrderId
            + " result=UNKNOWN cause=TEMPORARY_FAILURE error=SocketTimeoutException");
        assertThat(output).doesNotContain(CARD_NO);
        awaitLateCreateHandled();
    }

    @Test
    void lookupSuccessFollowedByRollbackLeavesSameDbButDifferentLogs(CapturedOutput output) {
        PG.dropNextCreateResponse();
        confirm();
        PG.changeStatus("TEST-TX-1", "SUCCESS");
        doThrow(new IllegalStateException("저장 실패 주입"))
            .when(paymentEventRepository).save(argThat(event -> event.getSource() == PaymentEvent.Source.RECONCILE));

        assertThatThrownBy(() -> orderFacade.reconcile(userId, orderId)).isInstanceOf(IllegalStateException.class);

        PaymentAttempt attempt = attempt();
        assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.UNKNOWN);
        assertThat(attempt.getTransactionKey()).isNull();
        assertThat(output).contains("PG 응답: op=lookup providerOrderId=" + providerOrderId()
            + " httpStatus=200 result=OBSERVED transactions=[TEST-TX-1:" + providerOrderId() + ":SUCCESS:3000]");
        assertThat(output).contains("결제 결과 저장 실패: orderId=" + orderId
            + " step=TX_B:RECONCILE error=IllegalStateException");
    }

    /**
     * 생성 응답(202·PENDING)을 받고 그 응답을 저장하는 TX B가 실패한 경우. 결제 시도는 TX A가 저장한 REQUESTED로 남고,
     * UNKNOWN만 세는 집계에는 잡히지 않는다. 저장 실패를 풀면 reconcile이 새 결제 없이 복구한다.
     */
    @Test
    void createResponseSaveFailureLeavesRequestedOutsideUnknownCount(CapturedOutput output) {
        PG.settleCreatedAs("SUCCESS");
        doThrow(new IllegalStateException("저장 실패 주입"))
            .when(paymentEventRepository).save(argThat(event -> event.getSource() == PaymentEvent.Source.CREATE_RESPONSE));

        assertThatThrownBy(this::confirm).isInstanceOf(IllegalStateException.class);

        PaymentAttempt attempt = attempt();
        long unknown = paymentAttemptJpaRepository.countByStatus(PaymentAttemptStatus.UNKNOWN);
        long requested = paymentAttemptJpaRepository.countByStatus(PaymentAttemptStatus.REQUESTED);
        System.out.printf("[create-save-failure] orderId=%d attempt=%s internalTxKey=%s unknownCount=%d requestedCount=%d "
                + "pgCreates=%d pgTransactions=%s%n", orderId, attempt.getStatus(), attempt.getTransactionKey(), unknown,
            requested, PG.createCalls(), PG.transactions());
        assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.REQUESTED);
        assertThat(attempt.getTransactionKey()).isNull();
        assertThat(unknown).isZero();
        assertThat(requested).isOne();
        assertThat(output).contains("PG 응답: op=create providerOrderId=" + providerOrderId()
            + " httpStatus=202 result=OBSERVED transactions=[TEST-TX-1:" + providerOrderId() + ":PENDING:3000]");
        assertThat(output).contains("결제 결과 저장 실패: orderId=" + orderId
            + " step=TX_B:CREATE_RESPONSE error=IllegalStateException");

        reset(paymentEventRepository);
        OrderPaymentInfo recovered = orderFacade.reconcile(userId, orderId);

        PaymentAttempt after = attempt();
        System.out.printf("[create-save-failure-reconcile] orderId=%d attempt=%s internalTxKey=%s order=%s pgCreates=%d%n",
            orderId, after.getStatus(), after.getTransactionKey(), recovered.order().status(), PG.createCalls());
        assertThat(after.getStatus()).isEqualTo(PaymentAttemptStatus.SUCCESS);
        assertThat(after.getTransactionKey()).isEqualTo("TEST-TX-1");
        assertThat(PG.createCalls()).isOne();
    }

    @Test
    void circuitOpenLookupLeavesDeferredLineWithoutCallingPg(CapturedOutput output) {
        PG.dropNextCreateResponse();
        confirm();
        resilientPaymentProvider.lookupCircuitBreaker().transitionToOpenState();
        int lookupsBefore = PG.lookupCalls();

        orderFacade.reconcile(userId, orderId);

        assertThat(PG.lookupCalls()).isEqualTo(lookupsBefore);
        assertThat(attempt().getStatus()).isEqualTo(PaymentAttemptStatus.UNKNOWN);
        assertThat(output).doesNotContain("PG 요청: op=lookup");
        assertThat(output).contains("결제 결과 확인 보류: orderId=" + orderId + " source=RECONCILE cause=CIRCUIT_OPEN");
    }

    @Test
    void lookupTimeoutLeavesAdapterLinesAndDeferredLine(CapturedOutput output) {
        PG.dropNextCreateResponse();
        confirm();
        PG.delayEveryResponse(Duration.ofMillis(700));

        orderFacade.reconcile(userId, orderId);

        assertThat(attempt().getStatus()).isEqualTo(PaymentAttemptStatus.UNKNOWN);
        assertThat(output).contains("PG 응답 없음: op=lookup providerOrderId=" + providerOrderId()
            + " result=UNKNOWN cause=TEMPORARY_FAILURE error=SocketTimeoutException");
        assertThat(output).contains("결제 결과 확인 보류: orderId=" + orderId + " source=RECONCILE cause=TEMPORARY_FAILURE");
    }

    /** PG는 늦게라도 거래를 만든다. 그 처리가 끝나기 전에 다음 테스트의 장애 설정을 가져가지 않게 기다린다. */
    private static void awaitLateCreateHandled() {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (PG.transactionCount() == 0) {
            assertThat(System.nanoTime()).as("늦은 생성 처리 대기").isLessThan(deadline);
            Thread.onSpinWait();
        }
    }

    private void confirm() {
        orderFacade.confirm(userId, orderId, new OrderFacade.PaymentCommand("attempt-" + orderId, "TEST", CARD_NO));
    }

    private PaymentAttempt attempt() {
        return paymentAttemptJpaRepository.findByOrderId(orderId).orElseThrow();
    }

    private String providerOrderId() {
        return attempt().getProviderOrderId();
    }

    private static FakePaymentServer startPg() {
        try {
            return new FakePaymentServer();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
