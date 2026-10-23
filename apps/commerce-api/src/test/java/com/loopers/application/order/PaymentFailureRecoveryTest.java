package com.loopers.application.order;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.loopers.application.payment.PaymentAttemptStarter;
import com.loopers.application.payment.PaymentRecoveryJob;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.payment.PaymentAttempt;
import com.loopers.domain.payment.PaymentAttemptRepository;
import com.loopers.domain.payment.PaymentAttemptStatus;
import com.loopers.domain.payment.PaymentConflictRepository;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.user.UserModel;
import com.loopers.infrastructure.order.OrderJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.support.pg.TestPg;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;

/**
 * 과제 검증 장면 중 "외부 성공 뒤 내부 저장 실패"와 "발송 기록 뒤 재시작"(5주차 실습 6번).
 * 둘 다 외부 거래와 내부 기록이 어긋난 채 남는 순간이다 — 회복은 create를 다시 보내지 않고 조회로만 맞춘다(INV-005).
 */
@SpringBootTest
class PaymentFailureRecoveryTest {

    private static final long PRICE = 2_000L;
    private static final int INITIAL_STOCK = 10;
    private static final int QUANTITY = 2;
    private static final long AMOUNT = PRICE * QUANTITY;

    @Autowired
    private OrderFacade orderFacade;

    @Autowired
    private PaymentAttemptStarter paymentAttemptStarter;

    @Autowired
    private PaymentRecoveryJob paymentRecoveryJob;

    @MockitoSpyBean
    private PaymentAttemptRepository paymentAttemptRepository;

    @Autowired
    private PaymentConflictRepository paymentConflictRepository;

    @Autowired
    private ProductJpaRepository productJpaRepository;

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Autowired
    private OrderJpaRepository orderJpaRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private final WireMockServer pg = TestPg.server();

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private record Fixture(Long userId, Long orderId, Long productId) {
        String providerOrderId() {
            return String.format("LP-ORD-%010d", orderId);
        }
    }

    private Fixture draft() {
        Long userId = userJpaRepository.save(new UserModel()).getId();
        Long productId = productJpaRepository.save(new ProductModel("상품", PRICE, 1L, INITIAL_STOCK)).getId();
        Long orderId = orderFacade.createOrder(userId, List.of(new OrderItemRequest(productId, QUANTITY))).id();
        return new Fixture(userId, orderId, productId);
    }

    private static ConfirmPaymentCommand command() {
        return new ConfirmPaymentCommand("key-1", "TEST", "0000-0000-0000-0000");
    }

    private PaymentAttempt attempt(Fixture fixture) {
        return paymentAttemptRepository.findByOrderId(fixture.orderId()).orElseThrow();
    }

    private int stockOf(Fixture fixture) {
        return productJpaRepository.findById(fixture.productId()).orElseThrow().getRemainingStock();
    }

    @DisplayName("PG에서 SUCCESS를 확인했는데 결과 반영 트랜잭션이 commit 직전에 실패하면 전부 rollback되고, "
        + "다음 확인이 같은 거래를 다시 조회해 한 번만 확정한다. create는 다시 보내지 않는다.")
    @Test
    void confirmsOnce_afterInternalSaveFailsFollowingExternalSuccess() {
        // arrange — 처리중 결제, PG에서는 이미 SUCCESS
        Fixture fixture = draft();
        orderFacade.confirmOrder(fixture.orderId(), fixture.userId(), command());
        assertThat(attempt(fixture).getStatus()).as("전제: 생성 요청이 수락돼 처리중이다").isEqualTo(PaymentAttemptStatus.PENDING);
        String transactionKey = TestPg.transactionKeyOf(fixture.providerOrderId());
        pg.stubFor(get(urlEqualTo(TestPg.PAYMENTS_PATH + "/" + transactionKey))
            .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                .withBody("{\"transactionKey\":\"" + transactionKey + "\",\"orderId\":\"" + fixture.providerOrderId()
                    + "\",\"amount\":" + AMOUNT + ",\"status\":\"SUCCESS\"}")));

        // 결과 반영(TX B)이 결제 시도를 잠근 직후, 그 트랜잭션의 commit 직전에 한 번만 실패시킨다
        AtomicBoolean failNextCommit = new AtomicBoolean(true);
        doAnswer(invocation -> {
            Object locked = invocation.callRealMethod();
            if (failNextCommit.getAndSet(false)) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void beforeCommit(boolean readOnly) {
                        throw new IllegalStateException("내부 저장 실패 주입");
                    }
                });
            }
            return locked;
        }).when(paymentAttemptRepository).findByOrderIdForUpdate(anyLong());

        // act — 첫 확인: 외부는 SUCCESS인데 내부 저장 실패
        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> orderFacade.reconcilePayment(fixture.orderId(), fixture.userId()));

        // assert — 내부는 전부 반영 전 그대로
        assertAll(
            () -> assertThat(failure.getMessage()).isEqualTo("내부 저장 실패 주입"),
            () -> assertThat(attempt(fixture).getStatus()).isEqualTo(PaymentAttemptStatus.PENDING),
            () -> assertThat(orderJpaRepository.findById(fixture.orderId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING),
            () -> assertThat(orderJpaRepository.findById(fixture.orderId()).orElseThrow().getPaidAmount()).isNull(),
            () -> assertThat(stockOf(fixture)).isEqualTo(INITIAL_STOCK - QUANTITY)
        );

        // act — 다시 확인: 같은 거래를 조회해 한 번 확정
        OrderInfo info = orderFacade.reconcilePayment(fixture.orderId(), fixture.userId());

        // assert
        assertAll(
            () -> assertThat(info.status()).isEqualTo(OrderStatus.CONFIRMED),
            () -> assertThat(info.paidAmount()).isEqualTo(AMOUNT),
            () -> assertThat(attempt(fixture).getTransactionKey()).isEqualTo(transactionKey),
            () -> assertThat(stockOf(fixture)).isEqualTo(INITIAL_STOCK - QUANTITY),
            () -> assertThat(paymentConflictRepository.findAllByAttemptId(attempt(fixture).getId())).isEmpty()
        );
        pg.verify(exactly(1), postRequestedFor(urlEqualTo(TestPg.PAYMENTS_PATH)));
    }

    @DisplayName("발송 시작을 기록한 뒤 create를 보내기 전에 서버가 멈췄다면, 재시작 뒤에도 시도와 예약이 그대로이고 "
        + "같은 키의 재요청·회복 작업 어느 쪽도 create를 자동으로 다시 보내지 않는다. (INV-005, D-018)")
    @Test
    void neverResends_afterRestartFollowingSendStartRecord() {
        // arrange — TX A만 commit되고 create는 나가지 않았다(서버가 그 사이 멈춤)
        Fixture fixture = draft();
        paymentAttemptStarter.start(fixture.orderId(), fixture.userId(), "key-1");
        pg.stubFor(get(urlPathEqualTo(TestPg.PAYMENTS_PATH))
            .withQueryParam("orderId", equalTo(fixture.providerOrderId()))
            .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("[]")));

        // act — 재시작 뒤: 고객이 같은 키로 다시 확정을 누르고, 회복 작업이 1분 뒤 대상을 조회한다
        OrderInfo retried = orderFacade.confirmOrder(fixture.orderId(), fixture.userId(), command());
        paymentRecoveryJob.runOnce(attempt(fixture).getNextCheckAt().plusSeconds(1));

        // assert — 저장된 시도·예약 유지, 요청됨 그대로(응답 유실과 구분되는 흔적), 자동 재발송 0
        PaymentAttempt attempt = attempt(fixture);
        assertAll(
            () -> assertThat(retried.payment().status()).isEqualTo(PaymentAttemptStatus.REQUESTED),
            () -> assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.REQUESTED),
            () -> assertThat(attempt.getEmptyLookupCount()).isEqualTo(1),
            () -> assertThat(orderJpaRepository.findById(fixture.orderId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING),
            () -> assertThat(stockOf(fixture)).isEqualTo(INITIAL_STOCK - QUANTITY)
        );
        pg.verify(exactly(0), postRequestedFor(urlEqualTo(TestPg.PAYMENTS_PATH)));
    }
}
