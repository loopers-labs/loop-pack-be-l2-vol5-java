package com.loopers.application.order;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.loopers.application.payment.PaymentRecoveryJob;
import com.loopers.application.payment.PaymentResultApplier;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.payment.PaymentAttempt;
import com.loopers.domain.payment.PaymentAttemptRepository;
import com.loopers.domain.payment.PaymentAttemptStatus;
import com.loopers.domain.payment.PaymentConflictReason;
import com.loopers.domain.payment.PaymentObservation;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.user.UserModel;
import com.loopers.infrastructure.order.OrderJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.support.pg.TestPg;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 관리자 결제 확인·실패 처리와 실패 처리 뒤 감시(docs/week5/design.md D-009, D-013, D-014, D-024, D-045).
 * 시간은 회복 작업에 넘기는 "지금"으로 흐르게 한다 — 저장된 처리 시각에 6시간·3일을 더해 부른다.
 */
@SpringBootTest
class OrderAdminPaymentTest {

    private static final long PRICE = 2_000L;
    private static final int INITIAL_STOCK = 10;
    private static final int QUANTITY = 2;
    private static final long AMOUNT = PRICE * QUANTITY;

    @Autowired
    private OrderAdminFacade orderAdminFacade;

    @Autowired
    private OrderFacade orderFacade;

    @Autowired
    private PaymentRecoveryJob paymentRecoveryJob;

    @Autowired
    private PaymentResultApplier paymentResultApplier;

    @Autowired
    private PaymentAttemptRepository paymentAttemptRepository;

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

    /** 처리중 — 테스트 PG 기본 응답(202). */
    private Fixture pending() {
        Fixture fixture = draft();
        orderFacade.confirmOrder(fixture.orderId(), fixture.userId(), command());
        return fixture;
    }

    /** 결과 미확인 — 생성 응답을 잃고 바로 재조회도 0건. 늦게 기록되는 생성 요청이 다음 테스트로 넘어가지 않게 기다린다. */
    private Fixture unknown() throws InterruptedException {
        Fixture fixture = draft();
        pg.stubFor(post(urlEqualTo(TestPg.PAYMENTS_PATH)).willReturn(aResponse().withStatus(202).withFixedDelay(1_000)));
        stubList(fixture, "[]");
        orderFacade.confirmOrder(fixture.orderId(), fixture.userId(), command());
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (pg.findAll(postRequestedFor(urlEqualTo(TestPg.PAYMENTS_PATH))).isEmpty() && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(20);
        }
        assertThat(attempt(fixture).getStatus()).isEqualTo(PaymentAttemptStatus.UNKNOWN);
        return fixture;
    }

    private void stubList(Fixture fixture, String body) {
        pg.stubFor(get(urlPathEqualTo(TestPg.PAYMENTS_PATH))
            .withQueryParam("orderId", equalTo(fixture.providerOrderId()))
            .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body)));
    }

    private static String transactionJson(Fixture fixture, String status) {
        return "{\"transactionKey\":\"TEST-TX-LATE\",\"orderId\":\"" + fixture.providerOrderId()
            + "\",\"amount\":" + AMOUNT + ",\"status\":\"" + status + "\"}";
    }

    private int listLookups(Fixture fixture) {
        return pg.findAll(getRequestedFor(urlPathEqualTo(TestPg.PAYMENTS_PATH))
            .withQueryParam("orderId", equalTo(fixture.providerOrderId()))).size();
    }

    private PaymentAttempt attempt(Fixture fixture) {
        return paymentAttemptRepository.findByOrderId(fixture.orderId()).orElseThrow();
    }

    private OrderStatus orderStatus(Fixture fixture) {
        return orderJpaRepository.findById(fixture.orderId()).orElseThrow().getStatus();
    }

    private int stockOf(Fixture fixture) {
        return productJpaRepository.findById(fixture.productId()).orElseThrow().getRemainingStock();
    }

    @DisplayName("관리자가 실패 처리하면, ")
    @Nested
    class Fail {
        @DisplayName("결과 미확인 결제를 FAILED로 끝내고 주문 실패·재고 반환을 하며, 처리자·처리 시각·감시 기간을 남긴다.")
        @Test
        void failsUnknownPayment() throws InterruptedException {
            // arrange
            Fixture fixture = unknown();

            // act
            OrderAdminInfo info = orderAdminFacade.failPayment(fixture.orderId(), "admin-kim");

            // assert
            OrderInfo.Payment payment = info.order().payment();
            assertAll(
                () -> assertThat(info.order().status()).isEqualTo(OrderStatus.PAYMENT_FAILED),
                () -> assertThat(payment.status()).isEqualTo(PaymentAttemptStatus.FAILED),
                () -> assertThat(payment.resolvedBy()).isEqualTo("admin-kim"),
                () -> assertThat(payment.resolvedAt()).isNotNull(),
                () -> assertThat(payment.watchUntil()).isEqualTo(payment.resolvedAt().plusDays(3)),
                () -> assertThat(stockOf(fixture)).isEqualTo(INITIAL_STOCK)
            );
        }

        @DisplayName("처리중 결제는 409이고, 상태·재고는 그대로다. (D-045)")
        @Test
        void conflicts_forPendingPayment() {
            // arrange
            Fixture fixture = pending();

            // act
            CoreException exception = assertThrows(CoreException.class,
                () -> orderAdminFacade.failPayment(fixture.orderId(), "admin"));

            // assert
            assertAll(
                () -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.CONFLICT),
                () -> assertThat(attempt(fixture).getStatus()).isEqualTo(PaymentAttemptStatus.PENDING),
                () -> assertThat(stockOf(fixture)).isEqualTo(INITIAL_STOCK - QUANTITY)
            );
        }

        @DisplayName("같은 결제를 두 번 실패 처리하면 두 번째는 409이고, 재고는 한 번만 돌려준다.")
        @Test
        void conflicts_whenAlreadyFailed() throws InterruptedException {
            // arrange
            Fixture fixture = unknown();
            orderAdminFacade.failPayment(fixture.orderId(), "admin");

            // act
            CoreException exception = assertThrows(CoreException.class,
                () -> orderAdminFacade.failPayment(fixture.orderId(), "admin"));

            // assert
            assertAll(
                () -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.CONFLICT),
                () -> assertThat(stockOf(fixture)).isEqualTo(INITIAL_STOCK)
            );
        }

        @DisplayName("결제를 시작하지 않은 주문은 409, 없는 주문은 404다.")
        @Test
        void conflictsOrNotFound_withoutAttempt() {
            Fixture fixture = draft();
            CoreException noAttempt = assertThrows(CoreException.class, () -> orderAdminFacade.failPayment(fixture.orderId(), "admin"));
            CoreException noOrder = assertThrows(CoreException.class, () -> orderAdminFacade.failPayment(999_999L, "admin"));
            assertAll(
                () -> assertThat(noAttempt.getErrorType()).isEqualTo(ErrorType.CONFLICT),
                () -> assertThat(noOrder.getErrorType()).isEqualTo(ErrorType.NOT_FOUND)
            );
        }
    }

    @DisplayName("관리자 실패 처리 뒤 감시는, (D-013)")
    @Nested
    class Watch {
        @DisplayName("6시간이 되기 전엔 조회하지 않고, 6시간 뒤 조회해 늦은 SUCCESS를 충돌로 기록하며 FAILED는 그대로 둔다. 3일이 지나면 더 조회하지 않는다.")
        @Test
        void looksUpEverySixHours_forThreeDays() throws InterruptedException {
            // arrange
            Fixture fixture = unknown();
            orderAdminFacade.failPayment(fixture.orderId(), "admin");
            ZonedDateTime failedAt = attempt(fixture).getResolvedAt();
            stubList(fixture, "[" + transactionJson(fixture, "SUCCESS") + "]");
            pg.resetRequests();

            // act & assert — 아직 6시간 전
            paymentRecoveryJob.runOnce(failedAt.plusHours(5).plusMinutes(59));
            assertThat(listLookups(fixture)).isZero();

            // 6시간 뒤: 늦게 나타난 SUCCESS
            paymentRecoveryJob.runOnce(failedAt.plusHours(6).plusSeconds(1));
            OrderAdminInfo detail = orderAdminFacade.getOrder(fixture.orderId());
            assertAll(
                () -> assertThat(listLookups(fixture)).isEqualTo(1),
                () -> assertThat(detail.order().status()).isEqualTo(OrderStatus.PAYMENT_FAILED),
                () -> assertThat(detail.order().payment().status()).isEqualTo(PaymentAttemptStatus.FAILED),
                () -> assertThat(detail.conflicts()).hasSize(1),
                () -> assertThat(detail.conflicts().get(0).reason()).isEqualTo(PaymentConflictReason.DIFFERENT_OUTCOME_AFTER_TERMINAL),
                () -> assertThat(detail.conflicts().get(0).observedStatus()).isEqualTo(PaymentObservation.Status.SUCCESS),
                () -> assertThat(stockOf(fixture)).isEqualTo(INITIAL_STOCK)
            );

            // 감시 기간(3일)이 끝난 뒤
            pg.resetRequests();
            paymentRecoveryJob.runOnce(failedAt.plusDays(3).plusMinutes(1));
            assertThat(listLookups(fixture)).isZero();
        }

        @DisplayName("관리자가 처리하지 않고 PG 결과로 끝난 결제는 감시하지 않는다.")
        @Test
        void doesNotWatchOrdinaryFailure() {
            // arrange
            Fixture fixture = pending();
            paymentResultApplier.apply(fixture.orderId(), new PaymentObservation(
                fixture.providerOrderId(), TestPg.transactionKeyOf(fixture.providerOrderId()), PaymentObservation.Status.FAILED, AMOUNT));
            pg.resetRequests();

            // act
            paymentRecoveryJob.runOnce(ZonedDateTime.now().plusHours(7));

            // assert
            assertThat(pg.findAll(getRequestedFor(urlPathEqualTo(TestPg.PAYMENTS_PATH + "/" + TestPg.transactionKeyOf(fixture.providerOrderId())))))
                .isEmpty();
        }
    }

    @DisplayName("관리자 단건 확인은, (D-014)")
    @Nested
    class AdminReconcile {
        @DisplayName("종결된 결제도 PG에 다시 묻고, 다른 결과면 상태를 바꾸지 않고 충돌로 기록한다.")
        @Test
        void recordsConflict_forTerminalPayment() throws InterruptedException {
            // arrange
            Fixture fixture = unknown();
            orderAdminFacade.failPayment(fixture.orderId(), "admin");
            stubList(fixture, "[" + transactionJson(fixture, "SUCCESS") + "]");

            // act
            OrderAdminInfo info = orderAdminFacade.reconcilePayment(fixture.orderId());

            // assert
            assertAll(
                () -> assertThat(info.order().payment().status()).isEqualTo(PaymentAttemptStatus.FAILED),
                () -> assertThat(info.conflicts()).extracting(OrderAdminInfo.Conflict::reason)
                    .containsExactly(PaymentConflictReason.DIFFERENT_OUTCOME_AFTER_TERMINAL)
            );
        }

        @DisplayName("같은 결과면 아무것도 바꾸지 않는다.")
        @Test
        void noEffect_forSameOutcome() throws InterruptedException {
            // arrange
            Fixture fixture = unknown();
            orderAdminFacade.failPayment(fixture.orderId(), "admin");
            stubList(fixture, "[" + transactionJson(fixture, "FAILED") + "]");

            // act
            OrderAdminInfo info = orderAdminFacade.reconcilePayment(fixture.orderId());

            // assert
            assertAll(
                () -> assertThat(info.order().payment().status()).isEqualTo(PaymentAttemptStatus.FAILED),
                () -> assertThat(info.conflicts()).isEmpty(),
                () -> assertThat(stockOf(fixture)).isEqualTo(INITIAL_STOCK)
            );
        }

        @DisplayName("0건이어도 연속 0건 횟수를 세지 않는다 — 회복 작업의 조회만 센다. (D-043)")
        @Test
        void doesNotCountEmptyLookup() throws InterruptedException {
            // arrange
            Fixture fixture = unknown();

            // act
            orderAdminFacade.reconcilePayment(fixture.orderId());
            orderAdminFacade.reconcilePayment(fixture.orderId());
            OrderAdminInfo info = orderAdminFacade.reconcilePayment(fixture.orderId());

            // assert
            assertAll(
                () -> assertThat(info.order().payment().emptyLookupCount()).isZero(),
                () -> assertThat(info.order().payment().reviewRequestedAt()).isNull(),
                () -> assertThat(orderStatus(fixture)).isEqualTo(OrderStatus.PAYMENT_PENDING)
            );
        }
    }
}
