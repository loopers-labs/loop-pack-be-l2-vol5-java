package com.loopers.application.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.order.OrderFacade;
import com.loopers.application.payment.dto.PaymentAttemptInfo;
import com.loopers.application.payment.dto.PaymentCheckInfo;
import com.loopers.application.payment.pg.PgPaymentRequestResult;
import com.loopers.application.payment.pg.PgPaymentStatusCheckResult;
import com.loopers.application.payment.pg.PgStatus;
import com.loopers.application.payment.pg.PgTransactionInfo;
import com.loopers.domain.order.OrderService;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.order.OrderStockService;
import com.loopers.domain.payment.PaymentAttemptService;
import com.loopers.domain.payment.PaymentAttemptStatus;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductService;
import com.loopers.domain.user.User;
import com.loopers.infrastructure.payment.PaymentGatewayImpl;
import com.loopers.infrastructure.payment.PaymentPgProperties;
import com.loopers.infrastructure.payment.PgHttpClient;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.support.pg.FakePgServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * PG 결과를 확인해 주문·재고에 반영하는 경로(고객의 확인, PG 의 알림)가 실제 DB 와 로컬 테스트 PG 를 지나며 남기는 효과를
 * 확인한다. 기준은 상태 이름이 아니라 <b>효과의 횟수</b>다: 외부 거래 수, create 호출 수, 조회 호출 수, 가용 재고, 포인트.
 *
 * <p>기본 시나리오는 재고 5개 상품을 수량 2개로 주문한 것이다(단가 3,500원, 합계 7,000원). 재고를 확보하면 3개이고, 성공해도
 * 3개다(추가 차감 없음). 확정 실패로 돌려주면 5개다. 7개가 되는 중복 반환이나 UNKNOWN 에서 5개로 돌아가는 성급한 반환은
 * 잘못된 처리다.
 */
class PaymentIntegrationTest extends PaymentFlowTestSupport {

    private static final String KEY = "attempt-1";
    private static final long USER_BALANCE = 10_000L;

    @Autowired
    private PaymentGateway paymentGateway;

    @Autowired
    private ProductService productService;

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderStockService orderStockService;

    @Autowired
    private PaymentAttemptService paymentAttemptService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private PaymentPgProperties pgProperties;

    private User user;
    private Product product;
    private Long orderId;

    private void arrange() {
        user = saveUser(USER_BALANCE);
        product = saveProduct("러닝화", 3_500, 5);
        orderId = placeOrder(user, product, 2);
    }

    // 결제를 시작해 PG 가 거래를 수락한 상태(PENDING)로 만든다.
    private void startAccepted() {
        arrange();
        orderFacade.confirm(command(user, orderId, KEY));
    }

    // 거래가 생긴 뒤 응답을 잃어 결과 미확인(UNKNOWN, 거래 키 없음)인 상태로 만든다.
    private void startWithLostResponse() {
        arrange();
        pg.onCreate(FakePgServer.acceptThenDropResponse());
        orderFacade.confirm(command(user, orderId, KEY));
    }

    // 거래가 만들어졌는지조차 모르는 결과 미확인 상태로 만든다. PG 에는 거래가 없다.
    private void startWithServerError() {
        arrange();
        pg.onCreate(FakePgServer.respond(500, "{\"code\":\"ERROR\"}"));
        orderFacade.confirm(command(user, orderId, KEY));
    }

    private PaymentCheckInfo reconcile() {
        return paymentResultCheckFacade.checkMyPaymentResult(user.getId(), orderId);
    }

    private PaymentCheckInfo callback(String transactionKey) {
        return paymentResultCheckFacade.checkPaymentResultOnPgCallback(providerOrderIdOf(orderId), transactionKey);
    }

    private CoreException rejected(Runnable action) {
        return assertThrows(CoreException.class, action::run);
    }

    @DisplayName("결과를 확인할 때,")
    @Nested
    class Reconcile {
        @DisplayName("PG 가 SUCCESS 라고 확인해야 CONFIRMED 가 된다. 추가 차감 없이 재고 3 을 유지하고, 포인트는 그대로이며 결제액이 저장된다.")
        @Test
        void confirms_onlyAfterPgSuccessIsVerified() {
            // arrange
            startAccepted();
            String key = pgKeyOf(orderId);

            // act & assert 1: PG 가 아직 처리 중이면 확정하지 않는다.
            PaymentCheckInfo pending = reconcile();
            assertThat(pending.info().orderStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
            assertThat(pending.info().settled()).isFalse();
            assertThat(pending.lookupFailed()).isFalse();
            assertThat(orderStatusOf(orderId)).isEqualTo("PAYMENT_PENDING");

            // act & assert 2: SUCCESS 를 확인한 뒤에만 확정한다.
            pg.setStatus(key, "SUCCESS");
            PaymentCheckInfo confirmed = reconcile();
            assertThat(confirmed.info().orderStatus()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(confirmed.info().attemptStatus()).isEqualTo(PaymentAttemptStatus.SUCCEEDED);
            assertThat(confirmed.info().settled()).isTrue();
            assertThat(orderStatusOf(orderId)).isEqualTo("CONFIRMED");
            assertThat(jdbcTemplate.queryForObject("select paid_amount from orders where id = ?", Long.class, orderId))
                .isEqualTo(7_000L);
            assertThat(stockOf(product)).isEqualTo(3);
            assertThat(balanceOf(user)).isEqualTo(USER_BALANCE);
            assertThat(pg.transactionCount()).isEqualTo(1);
            assertThat(pg.createCalls()).isEqualTo(1);
        }

        @DisplayName("PG 가 FAILED 라고 확인하면 PAYMENT_FAILED 이고 재고는 5 로 한 번 돌아온다. 다시 확인해도 PG 를 부르지 않고 7 이 되지 않는다.")
        @Test
        void returnsStockOnce_whenPgFailureIsVerified() {
            // arrange
            startAccepted();
            pg.setStatus(pgKeyOf(orderId), "FAILED");

            // act
            PaymentCheckInfo failed = reconcile();
            int lookupsAfterFirst = pg.lookupByKeyCalls();
            PaymentCheckInfo again = reconcile();

            // assert
            assertThat(failed.info().orderStatus()).isEqualTo(OrderStatus.PAYMENT_FAILED);
            assertThat(failed.info().attemptStatus()).isEqualTo(PaymentAttemptStatus.FAILED);
            assertThat(again.info()).isEqualTo(failed.info());
            assertThat(pg.lookupByKeyCalls()).as("종결된 시도는 PG 를 다시 조회하지 않는다").isEqualTo(lookupsAfterFirst);
            assertThat(stockOf(product)).isEqualTo(5);
            assertThat(balanceOf(user)).isEqualTo(USER_BALANCE);
            assertThat(pg.createCalls()).isEqualTo(1);
        }

        @DisplayName("거래가 생긴 뒤 응답만 유실돼도 같은 거래를 주문 식별자로 찾아 종결한다. 거래 1건, create 는 1회뿐이다.")
        @Test
        void settlesLostResponse_bySameTransaction_withoutResending() {
            // arrange
            startWithLostResponse();
            assertThat(attemptOf(orderId).get("status")).isEqualTo("UNKNOWN");
            assertThat(attemptOf(orderId).get("transaction_key")).isNull();
            String key = pgKeyOf(orderId);

            // act 1: 주문 식별자로 거래 1건을 찾는다. 처리 중이라 종결하지 않고 거래 키를 알게 된다.
            PaymentCheckInfo found = reconcile();

            // assert 1
            assertThat(found.info().attemptStatus()).isEqualTo(PaymentAttemptStatus.PENDING);
            assertThat(found.info().orderStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
            assertThat(attemptOf(orderId).get("transaction_key")).isEqualTo(key);
            assertThat(pg.lookupByOrderCalls()).isEqualTo(1);

            // act 2: 같은 거래가 SUCCESS 가 되면 거래 키로 조회해 종결한다.
            pg.setStatus(key, "SUCCESS");
            PaymentCheckInfo confirmed = reconcile();

            // assert 2
            assertThat(confirmed.info().orderStatus()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(pg.lookupByKeyCalls()).isEqualTo(1);
            assertThat(pg.createCalls()).isEqualTo(1);
            assertThat(pg.transactionCount()).isEqualTo(1);
            assertThat(stockOf(product)).isEqualTo(3);
        }

        @DisplayName("응답이 유실된 거래가 이미 SUCCESS 면 한 번의 확인으로 종결한다.")
        @Test
        void settlesLostResponse_whenAlreadySuccess() {
            // arrange
            startWithLostResponse();
            pg.setStatus(pgKeyOf(orderId), "SUCCESS");

            // act
            PaymentCheckInfo result = reconcile();

            // assert
            assertThat(result.info().orderStatus()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(attemptOf(orderId).get("transaction_key")).isEqualTo(pgKeyOf(orderId));
            assertThat(pg.createCalls()).isEqualTo(1);
        }

        @DisplayName("조회해도 거래가 없으면(0건) 상태를 바꾸지 않는다. 새 결제를 만들지 않고 재고도 돌려주지 않는다.")
        @Test
        void keepsUnknown_whenNoTransactionIsFound() {
            // arrange: PG 가 5xx 로 답해 거래가 생겼는지 모르고, 실제로도 거래가 없다.
            startWithServerError();

            // act
            PaymentCheckInfo result = reconcile();

            // assert
            assertThat(result.info().attemptStatus()).isEqualTo(PaymentAttemptStatus.UNKNOWN);
            assertThat(result.info().orderStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
            assertThat(result.lookupFailed()).isFalse();
            assertThat(attemptOf(orderId).get("anomaly")).isNull();
            assertThat(stockOf(product)).isEqualTo(3);
            assertThat(pg.createCalls()).as("거래가 안 보여도 create 를 다시 보내지 않는다").isEqualTo(1);
            assertThat(pg.transactionCount()).isZero();
        }

        @DisplayName("같은 주문의 거래가 둘 이상이면 하나를 고르지 않는다. 이상 징후만 남기고 확정하지 않는다.")
        @Test
        void keepsState_whenMultipleTransactionsAreFound() {
            // arrange
            startWithLostResponse();
            pg.addTransaction(providerOrderIdOf(orderId), 7_000, "SUCCESS");
            pg.addTransaction(providerOrderIdOf(orderId), 7_000, "SUCCESS");

            // act
            PaymentCheckInfo result = reconcile();

            // assert
            assertThat(result.info().attemptStatus()).isEqualTo(PaymentAttemptStatus.UNKNOWN);
            assertThat(result.info().orderStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
            assertThat(attemptOf(orderId).get("anomaly")).isEqualTo("MULTIPLE_TRANSACTIONS");
            assertThat(attemptOf(orderId).get("transaction_key")).isNull();
            assertThat(stockOf(product)).isEqualTo(3);
            assertThat(pg.createCalls()).isEqualTo(1);
        }

        @DisplayName("거래의 금액이 시도와 다르면 SUCCESS 여도 확정하지 않는다. 이상 징후만 남긴다.")
        @Test
        void doesNotConfirm_whenAmountDiffers() {
            // arrange: 거래는 있는데 금액이 7,000 이 아니다.
            startWithServerError();
            pg.addTransaction(providerOrderIdOf(orderId), 1, "SUCCESS");

            // act
            PaymentCheckInfo result = reconcile();

            // assert
            assertThat(result.info().orderStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
            assertThat(attemptOf(orderId).get("status")).isEqualTo("UNKNOWN");
            assertThat(attemptOf(orderId).get("anomaly")).isEqualTo("MISMATCH");
            assertThat(stockOf(product)).isEqualTo(3);
        }

        @DisplayName("알고 있는 거래 키로 조회했는데 PG 가 모르면 이상 징후만 남기고 상태는 그대로다. 실패로 확정하지 않는다.")
        @Test
        void keepsState_whenKnownTransactionIsNotFound() {
            // arrange: 거래 키를 안 뒤에 PG 쪽 거래가 사라졌다.
            startAccepted();
            pg.reset();

            // act
            PaymentCheckInfo result = reconcile();

            // assert
            assertThat(result.info().attemptStatus()).isEqualTo(PaymentAttemptStatus.PENDING);
            assertThat(result.info().orderStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
            assertThat(attemptOf(orderId).get("anomaly")).isEqualTo("TRANSACTION_NOT_FOUND");
            assertThat(stockOf(product)).isEqualTo(3);
        }

        @DisplayName("PG 를 조회하지 못하면 저장된 상태를 돌려주고 lookupUnavailable 을 표시한다. 실패로 확정하거나 재고를 돌려주지 않는다.")
        @Test
        void returnsStoredState_whenLookupIsUnavailable() {
            // arrange
            startAccepted();
            pg.failLookups(500);

            // act
            PaymentCheckInfo result = reconcile();

            // assert
            assertThat(result.lookupFailed()).isTrue();
            assertThat(result.info().attemptStatus()).isEqualTo(PaymentAttemptStatus.PENDING);
            assertThat(result.info().orderStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
            assertThat(pg.lookupByKeyCalls()).as("조회는 제한된 횟수만 재시도한다").isEqualTo(3);
            assertThat(stockOf(product)).isEqualTo(3);
            assertThat(orderStatusOf(orderId)).isEqualTo("PAYMENT_PENDING");

            // PG 가 회복하면 그대로 이어서 확인할 수 있다.
            pg.resetLookupFaults();
            pg.setStatus(pgKeyOf(orderId), "SUCCESS");
            assertThat(reconcile().info().orderStatus()).isEqualTo(OrderStatus.CONFIRMED);
        }

        @DisplayName("다른 사용자의 주문은 NOT_FOUND 이고, 결제를 시작하지 않은 주문은 CONFLICT 이다. 둘 다 PG 를 조회하지 않는다.")
        @Test
        void rejects_whenNotOwnerOrNoAttempt() {
            // arrange
            arrange();
            User stranger = saveUser(0);

            // act
            CoreException notOwner = rejected(() -> paymentResultCheckFacade.checkMyPaymentResult(stranger.getId(), orderId));
            CoreException noAttempt = rejected(() -> paymentResultCheckFacade.checkMyPaymentResult(user.getId(), orderId));

            // assert
            assertThat(notOwner.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
            assertThat(noAttempt.getErrorType()).isEqualTo(ErrorType.CONFLICT);
            assertThat(pg.lookupByKeyCalls() + pg.lookupByOrderCalls()).isZero();
        }
    }

    @DisplayName("PG 알림을 받을 때,")
    @Nested
    class Callback {
        @DisplayName("알림이 먼저 와도 조회가 먼저 와도 같은 결과로 수렴한다. 확정은 한 번이고 나중 입구는 PG 를 부르지 않는다.")
        @Test
        void convergesToSameResult_whicheverArrivesFirst() {
            // arrange
            startAccepted();
            String key = pgKeyOf(orderId);
            pg.setStatus(key, "SUCCESS");

            // act: 알림이 먼저
            PaymentCheckInfo byCallback = callback(key);
            int lookupsAfterCallback = pg.lookupByKeyCalls();
            PaymentCheckInfo byReconcile = reconcile();

            // assert
            assertThat(byCallback.info().orderStatus()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(byReconcile.info()).isEqualTo(byCallback.info());
            assertThat(pg.lookupByKeyCalls()).isEqualTo(lookupsAfterCallback);
            assertThat(stockOf(product)).isEqualTo(3);
        }

        @DisplayName("조회가 먼저 온 뒤의 알림은 같은 거래를 확인하고도 아무 효과가 없다.")
        @Test
        void hasNoEffect_whenReconcileCameFirst() {
            // arrange
            startAccepted();
            String key = pgKeyOf(orderId);
            pg.setStatus(key, "SUCCESS");
            PaymentCheckInfo first = reconcile();

            // act
            PaymentCheckInfo late = callback(key);

            // assert
            assertThat(late.info()).isEqualTo(first.info());
            assertThat(orderStatusOf(orderId)).isEqualTo("CONFIRMED");
            assertThat(stockOf(product)).isEqualTo(3);
        }

        @DisplayName("실패 알림을 중복해서 받아도 재고는 한 번만 돌아온다 (5 에서 7 이 되지 않는다).")
        @Test
        void returnsStockOnce_whenFailureCallbackIsDuplicated() {
            // arrange
            startAccepted();
            String key = pgKeyOf(orderId);
            pg.setStatus(key, "FAILED");

            // act
            callback(key);
            callback(key);
            callback(key);

            // assert
            assertThat(orderStatusOf(orderId)).isEqualTo("PAYMENT_FAILED");
            assertThat(stockOf(product)).isEqualTo(5);
        }

        @DisplayName("알림이 가리키는 거래가 PG 에서 아직 PENDING 이면 확정하지 않는다. 알림이 뭐라고 하든 조회한 사실만 반영한다.")
        @Test
        void doesNotConfirm_whenPgStillPending() {
            // arrange
            startAccepted();

            // act: 알림은 성공을 알린다고 가정하지만 PG 조회 결과는 PENDING 이다.
            PaymentCheckInfo result = callback(pgKeyOf(orderId));

            // assert
            assertThat(result.info().orderStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
            assertThat(orderStatusOf(orderId)).isEqualTo("PAYMENT_PENDING");
            assertThat(stockOf(product)).isEqualTo(3);
        }

        @DisplayName("다른 주문의 거래 키를 담은 위조 알림은 적용하지 않는다. 이상 징후만 남기고 아무것도 확정하지 않는다.")
        @Test
        void doesNotApplyOtherOrdersTransaction() {
            // arrange: 다른 주문의 거래가 SUCCESS 로 PG 에 있다.
            startAccepted();
            String otherKey = pg.addTransaction("LP-ORD-0000099999", 7_000, "SUCCESS").key();

            // act
            PaymentCheckInfo result = callback(otherKey);

            // assert
            assertThat(result.info().orderStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
            assertThat(attemptOf(orderId).get("anomaly")).isEqualTo("MISMATCH");
            assertThat(attemptOf(orderId).get("status")).isEqualTo("PENDING");
            assertThat(stockOf(product)).isEqualTo(3);
        }

        @DisplayName("PG 가 모르는 거래 키의 알림은 적용하지 않는다.")
        @Test
        void doesNotApply_whenTransactionKeyIsUnknownToPg() {
            // arrange
            startAccepted();

            // act
            PaymentCheckInfo result = callback("TEST-TX-FORGED");

            // assert
            assertThat(result.info().orderStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
            assertThat(attemptOf(orderId).get("anomaly")).isEqualTo("TRANSACTION_NOT_FOUND");
            assertThat(stockOf(product)).isEqualTo(3);
        }

        @DisplayName("모르는 providerOrderId 의 알림은 NOT_FOUND 이고 아무 효과도 없다. PG 를 조회하지도 않는다.")
        @Test
        void rejects_whenProviderOrderIdIsUnknown() {
            // arrange
            startAccepted();

            // act
            CoreException exception = rejected(() -> paymentResultCheckFacade.checkPaymentResultOnPgCallback("LP-ORD-0000099999", pgKeyOf(orderId)));

            // assert
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
            assertThat(pg.lookupByKeyCalls()).isZero();
            assertThat(orderStatusOf(orderId)).isEqualTo("PAYMENT_PENDING");
        }

        @DisplayName("응답이 유실된 시도에 알림이 오면 알림의 거래 키로 확인해 거래 키를 알게 되고 종결한다.")
        @Test
        void settlesLostResponse_byCallback() {
            // arrange
            startWithLostResponse();
            String key = pgKeyOf(orderId);
            pg.setStatus(key, "SUCCESS");

            // act
            PaymentCheckInfo result = callback(key);

            // assert
            assertThat(result.info().orderStatus()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(attemptOf(orderId).get("transaction_key")).isEqualTo(key);
            assertThat(pg.createCalls()).isEqualTo(1);
        }
    }

    @DisplayName("이미 종결된 시도에 결과가 다시 도착할 때,")
    @Nested
    class Settled {
        @DisplayName("같은 결과는 아무 효과가 없고, 다른 결과는 덮어쓰지 않고 상충만 기록한다.")
        @Test
        void keepsSettledResult_andRecordsConflict() {
            // arrange: SUCCESS 로 종결
            startAccepted();
            String key = pgKeyOf(orderId);
            pg.setStatus(key, "SUCCESS");
            reconcile();

            // act 1: 같은 결과
            updateByStatusCheckResult(orderId, PgPaymentStatusCheckResult.answered(List.of(new PgTransactionInfo(key, providerOrderIdOf(orderId), 7_000, PgStatus.SUCCESS))));

            // assert 1
            assertThat(attemptOf(orderId).get("anomaly")).isNull();

            // act 2: 같은 거래가 FAILED 로 관찰됨(상충)
            updateByStatusCheckResult(orderId, PgPaymentStatusCheckResult.answered(List.of(new PgTransactionInfo(key, providerOrderIdOf(orderId), 7_000, PgStatus.FAILED))));

            // assert 2
            assertThat(orderStatusOf(orderId)).isEqualTo("CONFIRMED");
            assertThat(attemptOf(orderId).get("status")).isEqualTo("SUCCEEDED");
            assertThat(attemptOf(orderId).get("anomaly")).isEqualTo("CONFLICTING_RESULT");
            assertThat(stockOf(product)).isEqualTo(3);
        }

        @DisplayName("종결 뒤에 도착한 처리 중 관찰은 낡은 정보라 무시한다. 이상 징후도 남기지 않는다.")
        @Test
        void ignoresStalePending() {
            // arrange
            startAccepted();
            String key = pgKeyOf(orderId);
            pg.setStatus(key, "SUCCESS");
            reconcile();

            // act
            updateByStatusCheckResult(orderId, PgPaymentStatusCheckResult.answered(List.of(new PgTransactionInfo(key, providerOrderIdOf(orderId), 7_000, PgStatus.PENDING))));

            // assert
            assertThat(attemptOf(orderId).get("status")).isEqualTo("SUCCEEDED");
            assertThat(attemptOf(orderId).get("anomaly")).isNull();
        }

        @DisplayName("알림이 먼저 종결한 뒤 늦게 돌아온 생성 응답이나 결과 미확인 처리가 CONFIRMED 를 되돌리지 못한다.")
        @Test
        void doesNotRegressConfirmed_whenLateCreateResultArrives() {
            // arrange: 응답 유실로 UNKNOWN 이었다가 알림으로 SUCCESS 종결
            startWithLostResponse();
            String key = pgKeyOf(orderId);
            pg.setStatus(key, "SUCCESS");
            callback(key);

            // act: 늦게 도착한 결과 미확인과, 같은 거래를 알리는 생성 응답
            updateByPaymentRequestResult(orderId, PgPaymentRequestResult.unknown());
            updateByPaymentRequestResult(orderId, PgPaymentRequestResult.accepted(
                new PgTransactionInfo(key, providerOrderIdOf(orderId), 7_000, PgStatus.PENDING)));

            // assert
            assertThat(orderStatusOf(orderId)).isEqualTo("CONFIRMED");
            assertThat(attemptOf(orderId).get("status")).isEqualTo("SUCCEEDED");
            assertThat(attemptOf(orderId).get("anomaly")).isNull();
            assertThat(stockOf(product)).isEqualTo(3);
        }
    }

    @DisplayName("발송 기록 뒤 프로세스가 재시작돼도,")
    @Nested
    class Restart {
        // 서비스·client 를 새로 만든다. DB 와 PG 의 거래 상태는 그대로다.
        private PaymentGateway restartedGateway() {
            return new PaymentGatewayImpl(new PgHttpClient(pgProperties, new ObjectMapper()));
        }

        private PaymentResultCheckFacade restartedFacade() {
            return new PaymentResultCheckFacade(
                restartedGateway(), paymentStatusUpdater, orderService, paymentAttemptService, transactionManager
            );
        }

        private OrderFacade restartedOrderFacade() {
            return new OrderFacade(
                productService, orderService, orderStockService, paymentAttemptService, restartedGateway(),
                paymentStatusUpdater, transactionManager
            );
        }

        @DisplayName("저장된 시도와 재고 확보가 유지되고, 거래가 보이지 않아도 자동으로 다시 발송하지 않는다.")
        @Test
        void doesNotResend_whenNoTransactionIsVisible() {
            // arrange: 발송을 시작한다고 기록하고 commit 한 직후 멈췄다. 실제 발송 여부는 로컬 기록만으로 알 수 없다.
            arrange();
            orderFacade.prepareConfirm(command(user, orderId, KEY));
            PaymentResultCheckFacade restarted = restartedFacade();

            // act
            PaymentCheckInfo result = restarted.checkMyPaymentResult(user.getId(), orderId);

            // assert
            assertThat(result.info().attemptStatus()).isEqualTo(PaymentAttemptStatus.REQUESTED);
            assertThat(result.info().orderStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
            assertThat(pg.createCalls()).isZero();
            assertThat(pg.transactionCount()).isZero();
            assertThat(stockOf(product)).isEqualTo(3);
            assertThat(attemptCount()).isEqualTo(1);
        }

        @DisplayName("이미 PG 에 나간 요청의 거래는 새 인스턴스가 조회로 찾아 종결한다. create 는 추가되지 않는다.")
        @Test
        void settlesTransactionThatWasAlreadySent() {
            // arrange: HTTP 가 끝난 뒤 내부 저장 전에 멈춘 상황. PG 에는 거래가 있고 우리 기록은 REQUESTED 다.
            arrange();
            orderFacade.prepareConfirm(command(user, orderId, KEY));
            pg.addTransaction(providerOrderIdOf(orderId), 7_000, "SUCCESS");
            PaymentResultCheckFacade restarted = restartedFacade();

            // act
            PaymentCheckInfo result = restarted.checkMyPaymentResult(user.getId(), orderId);

            // assert
            assertThat(result.info().orderStatus()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(attemptOf(orderId).get("transaction_key")).isEqualTo(pgKeyOf(orderId));
            assertThat(pg.createCalls()).isZero();
            assertThat(stockOf(product)).isEqualTo(3);
        }

        @DisplayName("같은 키로 다시 결제를 시작해도 발송 기록이 있는 시도를 다시 보내지 않는다.")
        @Test
        void doesNotResend_whenSameKeyIsStartedAgain() {
            // arrange
            arrange();
            orderFacade.prepareConfirm(command(user, orderId, KEY));
            PaymentResultCheckFacade restarted = restartedFacade();

            // act
            PaymentAttemptInfo info = restartedOrderFacade().confirm(command(user, orderId, KEY));

            // assert
            assertThat(info.attemptStatus()).isEqualTo(PaymentAttemptStatus.REQUESTED);
            assertThat(pg.createCalls()).isZero();
        }
    }
}
