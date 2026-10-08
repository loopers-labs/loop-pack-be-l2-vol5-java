package com.loopers.application.order;

import com.loopers.application.user.UserFacade;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.user.UserModel;
import com.loopers.domain.user.UserService;
import com.loopers.infrastructure.order.OrderJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * 실제 OrderFacade·UserFacade·repository·MySQL을 통과하는 경쟁 검증 (docs/week3/design.md 7번 섹션).
 * 시작만 latch로 맞추고 순서는 DB 잠금에 맡긴다. 요청별 결과를 성공·업무 거절·기술 오류로 집계하고,
 * 모든 worker가 끝난 뒤 새로 읽은 DB 상태와 불변식으로 대조한다. 테스트 전체를 부모 트랜잭션으로 감싸지 않는다.
 */
@SpringBootTest
class OrderConcurrencyTest {

    private static final long TIMEOUT_SECONDS = 30;

    @Autowired
    private OrderFacade orderFacade;

    @Autowired
    private UserFacade userFacade;

    @Autowired
    private UserService userService;

    @Autowired
    private ProductJpaRepository productJpaRepository;

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Autowired
    private OrderJpaRepository orderJpaRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("재고 5인 상품을 1개씩 사는 서로 다른 DRAFT 주문 8개를 동시에 확정하면, 확정 5·재고 부족 3·기술 오류 0이고 최종 재고는 0이다.")
    @Test
    void stockCompetition() throws Exception {
        // arrange
        int initialStock = 5;
        long price = 1000L;
        long initialBalance = 10_000L;
        Long productId = productJpaRepository.save(new ProductModel("에어맥스", price, 1L, initialStock)).getId();
        List<Long> buyerIds = new ArrayList<>();
        List<Long> orderIds = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            Long buyerId = createUserWithBalance(initialBalance);
            buyerIds.add(buyerId);
            orderIds.add(orderFacade.createOrder(buyerId, List.of(new OrderItemRequest(productId, 1))).id());
        }

        // act
        List<Callable<?>> requests = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            Long orderId = orderIds.get(i);
            Long buyerId = buyerIds.get(i);
            requests.add(() -> orderFacade.confirmOrder(orderId, buyerId));
        }
        Tally tally = runConcurrently(requests);

        // assert — 요청 집계
        assertAll(
            () -> assertThat(tally.technicalErrors()).as("기술 오류: %s", tally.technicalErrorDetails()).isEmpty(),
            () -> assertThat(tally.success()).isEqualTo(5),
            () -> assertThat(tally.outOfStock()).isEqualTo(3),
            () -> assertThat(tally.total()).isEqualTo(8)
        );

        // assert — DB 재조회와 불변식
        List<Order> confirmed = ordersWithStatus(OrderStatus.CONFIRMED);
        int confirmedQuantity = confirmed.stream()
            .flatMap(order -> order.getItems().stream())
            .filter(item -> item.getProductId().equals(productId))
            .mapToInt(OrderItem::getQuantity)
            .sum();
        int finalStock = productJpaRepository.findById(productId).orElseThrow().getRemainingStock();
        assertAll(
            () -> assertThat(confirmed).hasSize(tally.success()),
            () -> assertThat(ordersWithStatus(OrderStatus.DRAFT)).hasSize(tally.outOfStock()),
            () -> assertThat(finalStock).isZero(),
            () -> assertThat(initialStock - confirmedQuantity).isEqualTo(finalStock),
            () -> assertThat(confirmed).allSatisfy(order -> assertThat(order.getPaidAmount()).isEqualTo(price))
        );
        // 확정된 구매자만 결제액만큼 잔액이 줄고, 거절된 구매자 잔액은 그대로
        long totalBalance = buyerIds.stream().mapToLong(this::balanceOf).sum();
        assertThat(totalBalance).isEqualTo(initialBalance * 8 - price * tally.success());
    }

    @DisplayName("잔액 10,000원인 한 사용자가 서로 다른 상품의 4,000원 DRAFT 주문 3개를 동시에 확정하면, 확정 2·잔액 부족 1·기술 오류 0이고 최종 잔액은 2,000원이다.")
    @Test
    void pointCompetition() throws Exception {
        // arrange — 상품을 서로 다르게 두어 상품 잠금이 아니라 포인트(User) 잠금만이 이 경쟁을 직렬화하게 한다
        long initialBalance = 10_000L;
        long price = 4_000L;
        int initialStock = 10;
        Long userId = createUserWithBalance(initialBalance);
        List<Long> productIds = new ArrayList<>();
        List<Long> orderIds = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Long productId = productJpaRepository.save(new ProductModel("상품" + i, price, 1L, initialStock)).getId();
            productIds.add(productId);
            orderIds.add(orderFacade.createOrder(userId, List.of(new OrderItemRequest(productId, 1))).id());
        }

        // act
        List<Callable<?>> requests = new ArrayList<>();
        for (Long orderId : orderIds) {
            requests.add(() -> orderFacade.confirmOrder(orderId, userId));
        }
        Tally tally = runConcurrently(requests);

        // assert — 요청 집계
        assertAll(
            () -> assertThat(tally.technicalErrors()).as("기술 오류: %s", tally.technicalErrorDetails()).isEmpty(),
            () -> assertThat(tally.success()).isEqualTo(2),
            () -> assertThat(tally.insufficientPoints()).isEqualTo(1),
            () -> assertThat(tally.total()).isEqualTo(3)
        );

        // assert — DB 재조회와 불변식: 초기 잔액 − 성공 결제액 합 = 최종 잔액, 거절된 주문의 재고는 그대로
        List<Order> confirmed = ordersWithStatus(OrderStatus.CONFIRMED);
        long paidSum = confirmed.stream().mapToLong(Order::getPaidAmount).sum();
        List<Long> confirmedProductIds = confirmed.stream()
            .map(order -> order.getItems().get(0).getProductId())
            .toList();
        assertAll(
            () -> assertThat(confirmed).hasSize(2),
            () -> assertThat(ordersWithStatus(OrderStatus.DRAFT)).hasSize(1),
            () -> assertThat(balanceOf(userId)).isEqualTo(2_000L),
            () -> assertThat(initialBalance - paidSum).isEqualTo(balanceOf(userId))
        );
        for (Long productId : productIds) {
            int expectedStock = confirmedProductIds.contains(productId) ? initialStock - 1 : initialStock;
            assertThat(productJpaRepository.findById(productId).orElseThrow().getRemainingStock())
                .as("상품 %d의 재고", productId)
                .isEqualTo(expectedStock);
        }
    }

    @DisplayName("잔액 10,000원에서 2,000원 충전과 7,000원 주문 확정을 동시에 실행하면, 둘 다 성공·기술 오류 0이고 최종 잔액은 5,000원이다.")
    @Test
    void chargeAndPayCompetition() throws Exception {
        // arrange
        long initialBalance = 10_000L;
        long charge = 2_000L;
        long price = 7_000L;
        Long userId = createUserWithBalance(initialBalance);
        Long productId = productJpaRepository.save(new ProductModel("에어맥스", price, 1L, 10)).getId();
        Long orderId = orderFacade.createOrder(userId, List.of(new OrderItemRequest(productId, 1))).id();

        // act
        Tally tally = runConcurrently(List.of(
            () -> userFacade.chargePoint(userId, charge),
            () -> orderFacade.confirmOrder(orderId, userId)
        ));

        // assert — 초기 잔액 + 성공 충전액 − 성공 결제액 = 최종 잔액
        Order order = orderJpaRepository.findById(orderId).orElseThrow();
        assertAll(
            () -> assertThat(tally.technicalErrors()).as("기술 오류: %s", tally.technicalErrorDetails()).isEmpty(),
            () -> assertThat(tally.success()).isEqualTo(2),
            () -> assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED),
            () -> assertThat(balanceOf(userId)).isEqualTo(initialBalance + charge - order.getPaidAmount()),
            () -> assertThat(balanceOf(userId)).isEqualTo(5_000L)
        );
    }

    private Long createUserWithBalance(long balance) {
        Long userId = userJpaRepository.save(new UserModel()).getId();
        userService.chargePoint(userId, balance);
        return userId;
    }

    private long balanceOf(Long userId) {
        return userJpaRepository.findById(userId).orElseThrow().getPoint().getBalance();
    }

    private List<Order> ordersWithStatus(OrderStatus status) {
        return orderJpaRepository.findAll().stream()
            .filter(order -> order.getStatus() == status)
            .toList();
    }

    /**
     * 모든 요청을 준비시킨 뒤 동시에 출발시키고, 각 요청의 결과를 분류해 모은다. 출발만 맞출 뿐
     * 같은 값을 읽었다는 보장은 아니다 — 순서는 DB 잠금이 정한다. 대기에는 모두 제한 시간을 두고,
     * 끝나면 대기를 풀고 executor를 정리한다.
     */
    private Tally runConcurrently(List<Callable<?>> requests) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(requests.size());
        CountDownLatch ready = new CountDownLatch(requests.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Outcome>> futures = new ArrayList<>();
            for (Callable<?> request : requests) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        return Outcome.technical(new IllegalStateException("출발 신호 대기 시간 초과"));
                    }
                    try {
                        request.call();
                        return Outcome.SUCCESS;
                    } catch (Exception e) {
                        return Outcome.classify(e);
                    }
                }));
            }
            assertThat(ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).as("모든 요청이 준비됨").isTrue();
            start.countDown();

            List<Outcome> outcomes = new ArrayList<>();
            for (Future<Outcome> future : futures) {
                outcomes.add(future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            }
            return new Tally(outcomes);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private enum Kind { SUCCESS, OUT_OF_STOCK, INSUFFICIENT_POINTS, TECHNICAL_ERROR }

    private record Outcome(Kind kind, String detail) {
        static final Outcome SUCCESS = new Outcome(Kind.SUCCESS, null);

        static Outcome technical(Throwable e) {
            return new Outcome(Kind.TECHNICAL_ERROR, e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        /** 업무 거절(재고·잔액 부족)과 기술 오류(잠금 시간 초과·교착 등)를 섞지 않는다. */
        static Outcome classify(Exception e) {
            if (e instanceof CoreException core && core.getErrorType() == ErrorType.BAD_REQUEST) {
                String message = core.getCustomMessage();
                if (message != null && message.contains("재고")) {
                    return new Outcome(Kind.OUT_OF_STOCK, message);
                }
                if (message != null && message.contains("잔액")) {
                    return new Outcome(Kind.INSUFFICIENT_POINTS, message);
                }
            }
            return technical(e);
        }
    }

    private record Tally(List<Outcome> outcomes) {
        long count(Kind kind) {
            return outcomes.stream().filter(outcome -> outcome.kind() == kind).count();
        }

        int success() {
            return (int) count(Kind.SUCCESS);
        }

        int outOfStock() {
            return (int) count(Kind.OUT_OF_STOCK);
        }

        int insufficientPoints() {
            return (int) count(Kind.INSUFFICIENT_POINTS);
        }

        List<Outcome> technicalErrors() {
            return outcomes.stream().filter(outcome -> outcome.kind() == Kind.TECHNICAL_ERROR).toList();
        }

        List<String> technicalErrorDetails() {
            return technicalErrors().stream().map(Outcome::detail).toList();
        }

        int total() {
            return success() + outOfStock() + insufficientPoints() + technicalErrors().size();
        }
    }
}
