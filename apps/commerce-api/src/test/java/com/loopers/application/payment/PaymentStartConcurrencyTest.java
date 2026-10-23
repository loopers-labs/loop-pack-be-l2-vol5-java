package com.loopers.application.payment;

import com.loopers.application.point.PointFacade;
import com.loopers.domain.product.Product;
import com.loopers.domain.user.User;
import com.loopers.support.concurrent.ConcurrentRequests;
import com.loopers.support.concurrent.ConcurrentRequests.Results;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 같은 주문·같은 재고·같은 시도 키를 향한 결제 시작이 동시에 들어와도 PG 요청과 재고 확보가 한 번만 일어나는지 확인한다
 *. 메모리 잠금이 아니라 주문 행 잠금과 유일 제약이 지키는지를 실제 DB 로 본다.
 *
 * <p>요청 결과를 성공 / 업무 거절 / 기술 오류로 모으고, 모두 끝난 뒤 DB 와 PG fixture 를 새로 읽어 효과의 횟수와 맞춰 본다.
 * 경쟁은 확률적이므로 반복 실행한다. 재고 5개 상품을 수량 2개로 주문한 것이 기본 시나리오다(확보하면 3개).
 */
class PaymentStartConcurrencyTest extends PaymentFlowTestSupport {

    private static final int REPETITIONS = 5;
    private static final String KEY = "attempt-1";

    @Autowired
    private PointFacade pointFacade;

    private List<Runnable> requests(int count, IntFunction<Runnable> request) {
        List<Runnable> requests = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            requests.add(request.apply(i));
        }
        return requests;
    }

    private void assertAccountedFor(Results results, int requests) {
        assertThat(results.total()).isEqualTo(requests);
        assertThat(results.technicalErrors()).as("기술 오류: %s", results.outcomes()).isZero();
        assertThat(results.successes() + results.rejections()).isEqualTo(requests);
    }

    @DisplayName("같은 주문에 같은 시도 키로 동시에 요청하면, PG 요청·시도·재고 확보는 한 번뿐이고 모든 요청이 저장된 상태를 돌려받는다.")
    @RepeatedTest(REPETITIONS)
    void sendsOnce_whenSameAttemptIsRequestedConcurrently() {
        // arrange
        User user = saveUser(10_000);
        Product product = saveProduct("러닝화", 3_500, 5);
        Long orderId = placeOrder(user, product, 2);
        int requestCount = 6;

        // act
        Results results = ConcurrentRequests.run(
            requests(requestCount, i -> () -> orderFacade.confirm(command(user, orderId, KEY)))
        );

        // assert
        assertAccountedFor(results, requestCount);
        assertThat(results.successes()).isEqualTo(requestCount);
        assertThat(pg.createCalls()).isEqualTo(1);
        assertThat(pg.transactionCount()).isEqualTo(1);
        assertThat(attemptCount()).isEqualTo(1);
        assertThat(stockOf(product)).isEqualTo(3);
        assertThat(balanceOf(user)).isEqualTo(10_000L);
        assertThat(orderStatusOf(orderId)).isEqualTo("PAYMENT_PENDING");
    }

    @DisplayName("같은 주문에 서로 다른 키로 동시에 요청하면, 하나만 결제를 시작하고 나머지는 PAYMENT_ATTEMPT_CONFLICT 다.")
    @RepeatedTest(REPETITIONS)
    void startsOnlyOne_whenDifferentKeysAreRequestedConcurrently() {
        // arrange
        User user = saveUser(0);
        Product product = saveProduct("러닝화", 3_500, 5);
        Long orderId = placeOrder(user, product, 2);
        int requestCount = 4;

        // act
        Results results = ConcurrentRequests.run(
            requests(requestCount, i -> () -> orderFacade.confirm(command(user, orderId, "attempt-" + i)))
        );

        // assert
        assertAccountedFor(results, requestCount);
        assertThat(results.successes()).isEqualTo(1);
        assertThat(results.rejectedWith(ErrorType.PAYMENT_ATTEMPT_CONFLICT)).isEqualTo(requestCount - 1);
        assertThat(pg.createCalls()).isEqualTo(1);
        assertThat(attemptCount()).isEqualTo(1);
        assertThat(stockOf(product)).isEqualTo(3);
    }

    @DisplayName("재고가 한 주문 몫뿐인 상품을 두 주문이 동시에 결제하면, 하나만 재고를 확보하고 다른 하나는 PG 를 호출하지 않는다.")
    @RepeatedTest(REPETITIONS)
    void secures_onlyForOneOrder_whenStockIsOnlyEnoughForOne() {
        // arrange: 재고 3, 각 주문 수량 2
        User first = saveUser(0);
        User second = saveUser(0);
        Product product = saveProduct("러닝화", 3_500, 3);
        Long firstOrder = placeOrder(first, product, 2);
        Long secondOrder = placeOrder(second, product, 2);

        // act
        Results results = ConcurrentRequests.run(List.of(
            () -> orderFacade.confirm(command(first, firstOrder, "attempt-first")),
            () -> orderFacade.confirm(command(second, secondOrder, "attempt-second"))
        ));

        // assert
        assertAccountedFor(results, 2);
        assertThat(results.successes()).isEqualTo(1);
        assertThat(results.rejectedWith(ErrorType.OUT_OF_STOCK)).isEqualTo(1);
        assertThat(pg.createCalls()).isEqualTo(1);
        assertThat(attemptCount()).isEqualTo(1);
        assertThat(stockOf(product)).isEqualTo(1);
    }

    @DisplayName("서로 다른 주문이 같은 키를 동시에 쓰면, 하나만 성공하고 진 쪽이 차감한 재고는 함께 되돌아간다.")
    @RepeatedTest(REPETITIONS)
    void rollsBackStock_whenSameKeyIsUsedByDifferentOrdersConcurrently() {
        // arrange: 두 주문이 서로 다른 상품이라 상품 잠금으로 줄 서지 않고 유일 제약에서 만난다.
        User first = saveUser(0);
        User second = saveUser(0);
        Product firstProduct = saveProduct("러닝화", 3_500, 5);
        Product secondProduct = saveProduct("양말", 2_000, 5);
        Long firstOrder = placeOrder(first, firstProduct, 2);
        Long secondOrder = placeOrder(second, secondProduct, 2);

        // act
        Results results = ConcurrentRequests.run(List.of(
            () -> orderFacade.confirm(command(first, firstOrder, KEY)),
            () -> orderFacade.confirm(command(second, secondOrder, KEY))
        ));

        // assert: 재고는 이긴 쪽 몫(2개)만 줄어든다.
        assertAccountedFor(results, 2);
        assertThat(results.successes()).isEqualTo(1);
        assertThat(results.rejectedWith(ErrorType.PAYMENT_ATTEMPT_CONFLICT)).isEqualTo(1);
        assertThat(pg.createCalls()).isEqualTo(1);
        assertThat(attemptCount()).isEqualTo(1);
        assertThat(stockOf(firstProduct) + stockOf(secondProduct)).isEqualTo(10 - 2);
        long pendingOrders = jdbcTemplate.queryForObject(
            "select count(*) from orders where status = 'PAYMENT_PENDING'", Long.class
        );
        long draftOrders = jdbcTemplate.queryForObject("select count(*) from orders where status = 'DRAFT'", Long.class);
        assertThat(pendingOrders).isEqualTo(1);
        assertThat(draftOrders).isEqualTo(1);
    }

    @DisplayName("재고 5에 서로 다른 주문 8개를 동시에 결제하면, 5개만 재고를 확보하고 3개는 재고 부족으로 거절되며 PG 요청은 5건뿐이다.")
    @RepeatedTest(REPETITIONS)
    void securesOnlyAsManyOrdersAsStock_whenStartingConcurrently() {
        // arrange: 구매자별로 충분한 잔액을 둔다. 재고 부족이 아니면 거절될 이유가 없다.
        int initialStock = 5;
        int requestCount = 8;
        Product product = saveProduct("러닝화", 1_000, initialStock);
        List<User> buyers = new ArrayList<>();
        List<Long> orderIds = new ArrayList<>();
        for (int i = 0; i < requestCount; i++) {
            User buyer = saveUser(10_000);
            buyers.add(buyer);
            orderIds.add(placeOrder(buyer, product, 1));
        }

        // act
        Results results = ConcurrentRequests.run(
            requests(requestCount, i -> () -> orderFacade.confirm(command(buyers.get(i), orderIds.get(i), "attempt-" + i)))
        );

        // assert: 재고 부족으로 거절된 주문은 PG 에 요청하지 않고 DRAFT 로 남는다.
        assertAccountedFor(results, requestCount);
        assertThat(results.successes()).isEqualTo(5);
        assertThat(results.rejectedWith(ErrorType.OUT_OF_STOCK)).isEqualTo(3);
        assertThat(stockOf(product)).isZero();
        assertThat(pg.createCalls()).isEqualTo(5);
        assertThat(attemptCount()).isEqualTo(5);
        long pending = jdbcTemplate.queryForObject(
            "select count(*) from orders where status = 'PAYMENT_PENDING'", Long.class
        );
        long draft = jdbcTemplate.queryForObject("select count(*) from orders where status = 'DRAFT'", Long.class);
        assertThat(pending).isEqualTo(5);
        assertThat(draft).isEqualTo(3);
        buyers.forEach(buyer -> assertThat(balanceOf(buyer)).isEqualTo(10_000L));
    }

    @DisplayName("포인트 잔액이 주문 합계보다 적어도 결제 시작은 포인트와 무관하다. 3개 모두 시작하고 잔액은 그대로다.")
    @RepeatedTest(REPETITIONS)
    void startsRegardlessOfPointBalance_whenStartingConcurrently() {
        // arrange: 잔액 1,000 으로는 4,000 원 주문 하나도 포인트로 결제할 수 없다. 재고는 충분하다.
        int requestCount = 3;
        Product product = saveProduct("러닝화", 4_000, 100);
        User buyer = saveUser(1_000);
        List<Long> orderIds = new ArrayList<>();
        for (int i = 0; i < requestCount; i++) {
            orderIds.add(placeOrder(buyer, product, 1));
        }

        // act
        Results results = ConcurrentRequests.run(
            requests(requestCount, i -> () -> orderFacade.confirm(command(buyer, orderIds.get(i), "attempt-" + i)))
        );

        // assert
        assertAccountedFor(results, requestCount);
        assertThat(results.successes()).isEqualTo(requestCount);
        assertThat(results.rejections()).isZero();
        assertThat(balanceOf(buyer)).isEqualTo(1_000L);
        assertThat(stockOf(product)).isEqualTo(97);
        assertThat(pg.createCalls()).isEqualTo(requestCount);
    }

    @DisplayName("포인트 충전과 결제 시작을 동시에 하면 서로 영향이 없다. 충전은 유실되지 않고 결제 시작은 포인트를 차감하지 않는다.")
    @RepeatedTest(REPETITIONS)
    void keepsCharge_andDoesNotDeductPoint_whenChargingAndStartingConcurrently() {
        // arrange
        long initialBalance = 10_000;
        long chargeAmount = 2_000;
        Product product = saveProduct("러닝화", 7_000, 10);
        User buyer = saveUser(initialBalance);
        Long orderId = placeOrder(buyer, product, 1);

        // act
        Results results = ConcurrentRequests.run(List.of(
            () -> orderFacade.confirm(command(buyer, orderId, KEY)),
            () -> pointFacade.charge(buyer.getId(), chargeAmount)
        ));

        // assert
        assertAccountedFor(results, 2);
        assertThat(results.successes()).isEqualTo(2);
        assertThat(balanceOf(buyer)).isEqualTo(initialBalance + chargeAmount);
        assertThat(orderStatusOf(orderId)).isEqualTo("PAYMENT_PENDING");
    }
}
