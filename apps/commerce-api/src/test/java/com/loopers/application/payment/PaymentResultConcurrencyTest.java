package com.loopers.application.payment;

import com.loopers.domain.product.Product;
import com.loopers.domain.user.User;
import com.loopers.support.concurrent.ConcurrentRequests;
import com.loopers.support.concurrent.ConcurrentRequests.Results;
import com.loopers.support.pg.FakePgServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 같은 거래의 결과를 PG 알림과 고객의 확인이 동시에 반영하려 할 때 확정과 재고 반환이 한 번만 일어나는지 확인한다.
 * 모든 입구가 주문 행 잠금 아래의 같은 {@code apply} 를 거치므로 먼저 종결한 쪽이 이기고 나머지는 효과가 없어야 한다.
 *
 * <p>재고 5개 상품을 수량 2개로 주문한 것이 기본 시나리오다. 확보하면 3개이고, 성공해도 3개, 확정 실패로 돌려주면 5개다.
 * 중복 반환으로 7개가 되면 잘못된 처리다. 경쟁은 확률적이므로 반복 실행한다.
 */
class PaymentResultConcurrencyTest extends PaymentFlowTestSupport {

    private static final int REPETITIONS = 10;
    private static final int CALLBACKS = 4;
    private static final int RECONCILES = 4;

    private List<Runnable> callbacksAndReconciles(User user, Long orderId, String transactionKey) {
        List<Runnable> requests = new ArrayList<>();
        for (int i = 0; i < CALLBACKS; i++) {
            requests.add(() -> paymentResultCheckFacade.checkPaymentResultOnPgCallback(providerOrderIdOf(orderId), transactionKey));
        }
        for (int i = 0; i < RECONCILES; i++) {
            requests.add(() -> paymentResultCheckFacade.checkMyPaymentResult(user.getId(), orderId));
        }
        return requests;
    }

    private void assertAllSucceeded(Results results, int requests) {
        assertThat(results.total()).isEqualTo(requests);
        assertThat(results.technicalErrors()).as("기술 오류: %s", results.outcomes()).isZero();
        assertThat(results.successes()).as("%s", results.outcomes()).isEqualTo(requests);
    }

    @DisplayName("성공을 알리는 알림 4개와 확인 4개가 동시에 들어와도, 한 번만 확정되고 재고는 추가로 차감되지 않는다.")
    @RepeatedTest(REPETITIONS)
    void confirmsOnce_whenSuccessCallbackAndReconcileRace() {
        // arrange
        User user = saveUser(10_000);
        Product product = saveProduct("러닝화", 3_500, 5);
        Long orderId = placeOrder(user, product, 2);
        orderFacade.confirm(command(user, orderId, "attempt-1"));
        String key = pgKeyOf(orderId);
        pg.setStatus(key, "SUCCESS");

        // act
        Results results = ConcurrentRequests.run(callbacksAndReconciles(user, orderId, key));

        // assert
        assertAllSucceeded(results, CALLBACKS + RECONCILES);
        assertThat(orderStatusOf(orderId)).isEqualTo("CONFIRMED");
        assertThat(attemptOf(orderId)).containsEntry("status", "SUCCEEDED").containsEntry("transaction_key", key);
        assertThat(attemptOf(orderId).get("anomaly")).isNull();
        assertThat(stockOf(product)).isEqualTo(3);
        assertThat(balanceOf(user)).isEqualTo(10_000L);
        assertThat(pg.createCalls()).isEqualTo(1);
        assertThat(pg.transactionCount()).isEqualTo(1);
    }

    @DisplayName("실패를 알리는 알림 4개와 확인 4개가 동시에 들어와도, 재고는 한 번만 돌아온다 (5 → 3 → 5, 7 이 되지 않는다).")
    @RepeatedTest(REPETITIONS)
    void returnsStockOnce_whenFailureCallbackAndReconcileRace() {
        // arrange
        User user = saveUser(10_000);
        Product product = saveProduct("러닝화", 3_500, 5);
        Long orderId = placeOrder(user, product, 2);
        orderFacade.confirm(command(user, orderId, "attempt-1"));
        assertThat(stockOf(product)).isEqualTo(3);
        String key = pgKeyOf(orderId);
        pg.setStatus(key, "FAILED");

        // act
        Results results = ConcurrentRequests.run(callbacksAndReconciles(user, orderId, key));

        // assert
        assertAllSucceeded(results, CALLBACKS + RECONCILES);
        assertThat(orderStatusOf(orderId)).isEqualTo("PAYMENT_FAILED");
        assertThat(attemptOf(orderId)).containsEntry("status", "FAILED");
        assertThat(attemptOf(orderId).get("anomaly")).isNull();
        assertThat(stockOf(product)).isEqualTo(5);
        assertThat(balanceOf(user)).isEqualTo(10_000L);
    }

    @DisplayName("응답이 유실된 시도에 알림과 확인이 동시에 들어와도, 같은 거래 하나로 한 번만 종결하고 create 는 추가되지 않는다.")
    @RepeatedTest(REPETITIONS)
    void settlesOnce_whenLostResponseIsCheckedConcurrently() {
        // arrange: 거래가 생긴 뒤 응답을 잃어 UNKNOWN. 거래 키를 모르는 상태에서 알림과 확인이 함께 온다.
        User user = saveUser(10_000);
        Product product = saveProduct("러닝화", 3_500, 5);
        Long orderId = placeOrder(user, product, 2);
        pg.onCreate(FakePgServer.acceptThenDropResponse());
        orderFacade.confirm(command(user, orderId, "attempt-1"));
        String key = pgKeyOf(orderId);
        pg.setStatus(key, "FAILED");

        // act
        Results results = ConcurrentRequests.run(callbacksAndReconciles(user, orderId, key));

        // assert
        assertAllSucceeded(results, CALLBACKS + RECONCILES);
        assertThat(orderStatusOf(orderId)).isEqualTo("PAYMENT_FAILED");
        assertThat(attemptOf(orderId)).containsEntry("transaction_key", key);
        assertThat(stockOf(product)).isEqualTo(5);
        assertThat(pg.createCalls()).isEqualTo(1);
        assertThat(pg.transactionCount()).isEqualTo(1);
    }
}
