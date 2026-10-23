package com.loopers.application.payment;

import com.loopers.application.payment.dto.PaymentAttemptInfo;
import com.loopers.application.payment.pg.PgPaymentRequestResult;
import com.loopers.application.payment.pg.PgStatus;
import com.loopers.application.payment.pg.PgTransactionInfo;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.payment.PaymentAttemptStatus;
import com.loopers.domain.product.Product;
import com.loopers.domain.user.User;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.support.pg.FakePgServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 결제 시작(TX A → PG 호출 → TX B)이 실제 DB 와 로컬 테스트 PG 를 지나며 주문·재고·시도에 남기는 효과를 확인한다. 기준은 상태
 * 이름이 아니라 <b>효과의 횟수</b>다: 외부 거래 수, create 호출 수, 가용 재고, 포인트 잔액.
 *
 * <p>기본 시나리오는 재고 5개 상품을 수량 2개로 주문한 것이다(단가 3,500원, 합계 7,000원). 재고를 확보하면 가용 재고는
 * 3개이고, 확정 실패로 돌려주면 5개다.
 */
class PaymentStartIntegrationTest extends PaymentFlowTestSupport {

    private static final String KEY = "attempt-1";
    private static final long USER_BALANCE = 10_000L;

    private User user;
    private Product product;
    private Long orderId;

    private void arrange() {
        user = saveUser(USER_BALANCE);
        product = saveProduct("러닝화", 3_500, 5);
        orderId = placeOrder(user, product, 2);
    }

    private CoreException rejected(Runnable action) {
        return assertThrows(CoreException.class, action::run);
    }

    @DisplayName("결제를 시작할 때,")
    @Nested
    class Start {
        @DisplayName("PG 가 거래를 수락하면 PENDING 이다. 외부 거래 1건, create 1회, 재고 5 → 3, 포인트 변화 0.")
        @Test
        void keepsPending_whenPgAccepts() {
            // arrange
            arrange();

            // act
            PaymentAttemptInfo info = orderFacade.confirm(command(user, orderId, KEY));

            // assert
            assertThat(info.orderStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
            assertThat(info.attemptStatus()).isEqualTo(PaymentAttemptStatus.PENDING);
            assertThat(info.amount()).isEqualTo(7_000L);
            assertThat(info.settled()).isFalse();
            assertThat(pg.createCalls()).isEqualTo(1);
            assertThat(pg.transactionCount()).isEqualTo(1);
            assertThat(stockOf(product)).isEqualTo(3);
            assertThat(balanceOf(user)).isEqualTo(USER_BALANCE);
            assertThat(orderStatusOf(orderId)).isEqualTo("PAYMENT_PENDING");
            Map<String, Object> attempt = attemptOf(orderId);
            assertThat(attempt.get("status")).isEqualTo("PENDING");
            assertThat(attempt.get("transaction_key")).isEqualTo(pg.transactionsOf(providerOrderIdOf(orderId)).get(0).key());
        }

        @DisplayName("PG 로 보내는 금액은 요청이 아니라 저장된 주문 합계이고, 주문 식별자는 providerOrderId 다.")
        @Test
        void sendsStoredOrderTotal_withProviderOrderId() throws Exception {
            // arrange
            arrange();

            // act
            orderFacade.confirm(command(user, orderId, KEY));

            // assert
            var body = pg.lastRequest().json();
            assertThat(body.get("amount").asLong()).isEqualTo(7_000L);
            assertThat(body.get("orderId").asText()).isEqualTo(providerOrderIdOf(orderId));
        }

        @DisplayName("카드 번호는 DB 어디에도 저장하지 않는다.")
        @Test
        void doesNotPersistCardNumber() {
            // arrange
            arrange();

            // act
            orderFacade.confirm(command(user, orderId, KEY));

            // assert
            assertThat(jdbcTemplate.queryForList("select * from payment_attempts").toString()).doesNotContain(CARD_NO);
            assertThat(jdbcTemplate.queryForList("select * from orders").toString()).doesNotContain(CARD_NO);
        }

        @DisplayName("거래가 생긴 뒤 응답만 유실되면 결과 미확인이다. 재고는 유지하고, 거래 1건·create 1회이며 다시 보내지 않는다.")
        @Test
        void keepsUnknown_whenResponseIsLost() {
            // arrange
            arrange();
            pg.onCreate(FakePgServer.acceptThenDropResponse());

            // act
            PaymentAttemptInfo info = orderFacade.confirm(command(user, orderId, KEY));

            // assert
            assertThat(info.orderStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
            assertThat(info.attemptStatus()).isEqualTo(PaymentAttemptStatus.UNKNOWN);
            assertThat(pg.transactionCount()).isEqualTo(1);
            assertThat(pg.createCalls()).isEqualTo(1);
            assertThat(stockOf(product)).isEqualTo(3);
            assertThat(balanceOf(user)).isEqualTo(USER_BALANCE);
            assertThat(attemptOf(orderId).get("transaction_key")).isNull();
        }

        @DisplayName("PG 가 5xx 로 응답해도 결과 미확인이다. 실패로 확정하지 않고 재고도 돌려주지 않는다.")
        @Test
        void keepsUnknown_whenPgReturnsServerError() {
            // arrange
            arrange();
            pg.onCreate(FakePgServer.respond(500, "{\"code\":\"ERROR\"}"));

            // act
            PaymentAttemptInfo info = orderFacade.confirm(command(user, orderId, KEY));

            // assert
            assertThat(info.attemptStatus()).isEqualTo(PaymentAttemptStatus.UNKNOWN);
            assertThat(info.orderStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
            assertThat(pg.createCalls()).isEqualTo(1);
            assertThat(stockOf(product)).isEqualTo(3);
        }

        @DisplayName("거래 생성 전 확정 거절이면 FAILED 다. 거래 0건, create 1회, 재고는 5 로 한 번만 돌아온다.")
        @Test
        void failsAndReturnsStockOnce_whenRejectedBeforeCreate() {
            // arrange
            arrange();
            pg.onCreate(FakePgServer.rejectBeforeCreate());

            // act
            PaymentAttemptInfo info = orderFacade.confirm(command(user, orderId, KEY));

            // assert
            assertThat(info.orderStatus()).isEqualTo(OrderStatus.PAYMENT_FAILED);
            assertThat(info.attemptStatus()).isEqualTo(PaymentAttemptStatus.FAILED);
            assertThat(info.settled()).isTrue();
            assertThat(pg.transactionCount()).isZero();
            assertThat(pg.createCalls()).isEqualTo(1);
            assertThat(stockOf(product)).isEqualTo(5);
            assertThat(balanceOf(user)).isEqualTo(USER_BALANCE);
            assertThat(attemptOf(orderId).get("transaction_key")).isNull();
        }

        @DisplayName("같은 거절을 다시 적용해도 재고는 늘지 않는다 (5 에서 7 이 되지 않는다).")
        @Test
        void doesNotReturnStockTwice_whenSameRejectionIsAppliedAgain() {
            // arrange
            arrange();
            pg.onCreate(FakePgServer.rejectBeforeCreate());
            orderFacade.confirm(command(user, orderId, KEY));

            // act
            updateByPaymentRequestResult(orderId, PgPaymentRequestResult.rejectedWithoutTransaction());
            updateByPaymentRequestResult(orderId, PgPaymentRequestResult.rejectedWithoutTransaction());

            // assert
            assertThat(stockOf(product)).isEqualTo(5);
            assertThat(orderStatusOf(orderId)).isEqualTo("PAYMENT_FAILED");
        }
    }

    @DisplayName("PG 를 호출하기 전에 거절될 때,")
    @Nested
    class RejectedBeforePg {
        private void assertNothingHappened(int expectedStock, String expectedOrderStatus) {
            assertThat(pg.createCalls()).isZero();
            assertThat(pg.transactionCount()).isZero();
            assertThat(attemptCount()).isZero();
            assertThat(stockOf(product)).isEqualTo(expectedStock);
            assertThat(orderStatusOf(orderId)).isEqualTo(expectedOrderStatus);
        }

        @DisplayName("재고가 부족하면 OUT_OF_STOCK 이고 PG 를 호출하지 않는다. 주문은 DRAFT, 재고는 그대로다.")
        @Test
        void rejectsWithOutOfStock_withoutCallingPg() {
            // arrange: 주문을 만든 뒤 재고가 줄었다.
            arrange();
            jdbcTemplate.update("update products set stock = 1 where id = ?", product.getId());

            // act
            CoreException exception = rejected(() -> orderFacade.confirm(command(user, orderId, KEY)));

            // assert
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.OUT_OF_STOCK);
            assertNothingHappened(1, "DRAFT");
        }

        @DisplayName("상품이 삭제됐으면 PRODUCT_UNAVAILABLE 이고 주문 당시 상품명으로 알린다. PG 를 호출하지 않는다.")
        @Test
        void rejectsWithProductUnavailable_withoutCallingPg() {
            // arrange
            arrange();
            jdbcTemplate.update("update products set deleted_at = now() where id = ?", product.getId());

            // act
            CoreException exception = rejected(() -> orderFacade.confirm(command(user, orderId, KEY)));

            // assert
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.PRODUCT_UNAVAILABLE);
            assertThat(exception.getMessage()).contains("러닝화");
            assertNothingHappened(5, "DRAFT");
        }

        @DisplayName("다른 사용자의 주문이면 NOT_FOUND 이고 PG 를 호출하지 않는다.")
        @Test
        void rejectsWithNotFound_whenOrderBelongsToSomeoneElse() {
            // arrange
            arrange();
            User stranger = saveUser(0);

            // act
            CoreException exception = rejected(() -> orderFacade.confirm(command(stranger, orderId, KEY)));

            // assert
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
            assertNothingHappened(5, "DRAFT");
        }

        @DisplayName("이미 확정된 주문(포인트로 결제한 과거 주문)이면 ORDER_ALREADY_CONFIRMED 이고 PG 를 호출하지 않는다.")
        @Test
        void rejectsWithAlreadyConfirmed_whenOrderIsAlreadyConfirmed() {
            // arrange
            arrange();
            jdbcTemplate.update("update orders set status = 'CONFIRMED', paid_amount = 7000 where id = ?", orderId);

            // act
            CoreException exception = rejected(() -> orderFacade.confirm(command(user, orderId, KEY)));

            // assert
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.ORDER_ALREADY_CONFIRMED);
            assertNothingHappened(5, "CONFIRMED");
        }
    }

    @DisplayName("이미 시도가 있는 주문에 다시 요청할 때,")
    @Nested
    class Repeat {
        @DisplayName("같은 키면 저장된 결과를 돌려주고 PG 를 다시 호출하지 않는다. 재고·거래 수도 그대로다.")
        @Test
        void returnsStoredResult_withoutSendingAgain() {
            // arrange
            arrange();
            PaymentAttemptInfo first = orderFacade.confirm(command(user, orderId, KEY));

            // act
            PaymentAttemptInfo second = orderFacade.confirm(command(user, orderId, KEY));

            // assert
            assertThat(second).isEqualTo(first);
            assertThat(pg.createCalls()).isEqualTo(1);
            assertThat(pg.transactionCount()).isEqualTo(1);
            assertThat(stockOf(product)).isEqualTo(3);
            assertThat(attemptCount()).isEqualTo(1);
        }

        @DisplayName("결과 미확인인 시도에 같은 키로 다시 요청해도 새로 보내지 않는다.")
        @Test
        void doesNotResend_whenAttemptIsUnknown() {
            // arrange
            arrange();
            pg.onCreate(FakePgServer.acceptThenDropResponse());
            orderFacade.confirm(command(user, orderId, KEY));

            // act
            PaymentAttemptInfo again = orderFacade.confirm(command(user, orderId, KEY));

            // assert
            assertThat(again.attemptStatus()).isEqualTo(PaymentAttemptStatus.UNKNOWN);
            assertThat(pg.createCalls()).isEqualTo(1);
            assertThat(pg.transactionCount()).isEqualTo(1);
            assertThat(stockOf(product)).isEqualTo(3);
        }

        @DisplayName("확정 실패한 주문에 같은 키로 요청하면 저장된 FAILED 를 돌려주고, 다시 보내거나 재고를 건드리지 않는다.")
        @Test
        void returnsStoredFailure_whenAttemptFailed() {
            // arrange
            arrange();
            pg.onCreate(FakePgServer.rejectBeforeCreate());
            orderFacade.confirm(command(user, orderId, KEY));

            // act
            PaymentAttemptInfo again = orderFacade.confirm(command(user, orderId, KEY));

            // assert
            assertThat(again.attemptStatus()).isEqualTo(PaymentAttemptStatus.FAILED);
            assertThat(again.settled()).isTrue();
            assertThat(pg.createCalls()).isEqualTo(1);
            assertThat(stockOf(product)).isEqualTo(5);
        }

        @DisplayName("다른 키를 보내도 두 번째 시도를 만들지 않는다. PAYMENT_ATTEMPT_CONFLICT 이고 PG·재고는 그대로다.")
        @Test
        void rejectsWithConflict_whenKeyDiffers() {
            // arrange
            arrange();
            orderFacade.confirm(command(user, orderId, KEY));

            // act
            CoreException exception = rejected(() -> orderFacade.confirm(command(user, orderId, "attempt-2")));

            // assert
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.PAYMENT_ATTEMPT_CONFLICT);
            assertThat(pg.createCalls()).isEqualTo(1);
            assertThat(attemptCount()).isEqualTo(1);
            assertThat(stockOf(product)).isEqualTo(3);
        }

        @DisplayName("확정 실패한 주문에 새 키를 보내도 다시 결제하지 않는다.")
        @Test
        void rejectsWithConflict_whenKeyDiffersAfterFailure() {
            // arrange
            arrange();
            pg.onCreate(FakePgServer.rejectBeforeCreate());
            orderFacade.confirm(command(user, orderId, KEY));

            // act
            CoreException exception = rejected(() -> orderFacade.confirm(command(user, orderId, "attempt-2")));

            // assert
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.PAYMENT_ATTEMPT_CONFLICT);
            assertThat(pg.createCalls()).isEqualTo(1);
            assertThat(stockOf(product)).isEqualTo(5);
            assertThat(orderStatusOf(orderId)).isEqualTo("PAYMENT_FAILED");
        }

        @DisplayName("다른 주문에 쓰인 키는 거절한다. 두 번째 주문은 DRAFT 로 남고 재고도 차감되지 않는다.")
        @Test
        void rejectsWithConflict_whenKeyBelongsToOtherOrder() {
            // arrange
            arrange();
            orderFacade.confirm(command(user, orderId, KEY));
            User other = saveUser(0);
            Long otherOrderId = placeOrder(other, product, 1);

            // act
            CoreException exception = rejected(() -> orderFacade.confirm(command(other, otherOrderId, KEY)));

            // assert
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.PAYMENT_ATTEMPT_CONFLICT);
            assertThat(orderStatusOf(otherOrderId)).isEqualTo("DRAFT");
            assertThat(stockOf(product)).isEqualTo(3);
            assertThat(pg.createCalls()).isEqualTo(1);
            assertThat(attemptCount()).isEqualTo(1);
        }
    }

    @DisplayName("이미 나아간 상태에 늦은 결과가 도착할 때,")
    @Nested
    class LateResults {
        @DisplayName("PENDING 을 늦게 도착한 결과 미확인으로 되돌리지 않는다.")
        @Test
        void doesNotRegressPendingToUnknown() {
            // arrange
            arrange();
            orderFacade.confirm(command(user, orderId, KEY));

            // act
            PaymentAttemptInfo info = updateByPaymentRequestResult(orderId, PgPaymentRequestResult.unknown());

            // assert
            assertThat(info.attemptStatus()).isEqualTo(PaymentAttemptStatus.PENDING);
            assertThat(attemptOf(orderId).get("status")).isEqualTo("PENDING");
        }

        @DisplayName("종결된 FAILED 를 늦게 도착한 수락으로 되돌리지 않는다. 상태·재고는 그대로이고 상충만 기록한다.")
        @Test
        void doesNotRevertFailed_andRecordsConflict() {
            // arrange
            arrange();
            pg.onCreate(FakePgServer.rejectBeforeCreate());
            orderFacade.confirm(command(user, orderId, KEY));
            PgTransactionInfo lateTransaction =
                new PgTransactionInfo("TEST-TX-LATE", providerOrderIdOf(orderId), 7_000, PgStatus.PENDING);

            // act
            PaymentAttemptInfo info =
                updateByPaymentRequestResult(orderId, PgPaymentRequestResult.accepted(lateTransaction));

            // assert
            assertThat(info.attemptStatus()).isEqualTo(PaymentAttemptStatus.FAILED);
            assertThat(info.orderStatus()).isEqualTo(OrderStatus.PAYMENT_FAILED);
            assertThat(stockOf(product)).isEqualTo(5);
            Map<String, Object> attempt = attemptOf(orderId);
            assertThat(attempt.get("transaction_key")).isNull();
            assertThat(attempt.get("anomaly")).isEqualTo("CONFLICTING_RESULT");
        }

        @DisplayName("거래가 있는 PENDING 시도에 거래 없음 거절이 늦게 오면 덮어쓰지 않는다. 재고도 돌려주지 않는다.")
        @Test
        void doesNotFailPending_whenRejectionArrivesLate() {
            // arrange
            arrange();
            orderFacade.confirm(command(user, orderId, KEY));

            // act
            PaymentAttemptInfo info =
                updateByPaymentRequestResult(orderId, PgPaymentRequestResult.rejectedWithoutTransaction());

            // assert
            assertThat(info.attemptStatus()).isEqualTo(PaymentAttemptStatus.PENDING);
            assertThat(info.orderStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
            assertThat(stockOf(product)).isEqualTo(3);
            assertThat(attemptOf(orderId).get("anomaly")).isEqualTo("CONFLICTING_RESULT");
        }
    }

    @DisplayName("재고를 돌려줄 때,")
    @Nested
    class ReturnStock {
        @DisplayName("그사이 삭제된 상품도 수량을 돌려받고, 삭제 상태는 그대로라 다시 노출되지 않는다.")
        @Test
        void returnsStockToDeletedProduct_andKeepsItDeleted() {
            // arrange: 결과 미확인으로 재고 3 을 쥔 채, 상품이 삭제된다.
            arrange();
            pg.onCreate(FakePgServer.acceptThenDropResponse());
            orderFacade.confirm(command(user, orderId, KEY));
            jdbcTemplate.update("update products set deleted_at = now() where id = ?", product.getId());

            // act: 이 확정 거절은 결과 미확인 시도를 FAILED 로 종결하는 경로를 쓴다(조회로 FAILED 를 확인했을 때와 같은 반환).
            PaymentAttemptInfo info =
                updateByPaymentRequestResult(orderId, PgPaymentRequestResult.rejectedWithoutTransaction());

            // assert
            assertThat(info.orderStatus()).isEqualTo(OrderStatus.PAYMENT_FAILED);
            assertThat(stockOf(product)).isEqualTo(5);
            assertThat(jdbcTemplate.queryForObject(
                "select deleted_at from products where id = ?", java.sql.Timestamp.class, product.getId()
            )).isNotNull();
            assertThat(productRepository.findAllActiveByIds(List.of(product.getId()))).isEmpty();
        }
    }
}
