package com.loopers.concurrency;

import com.loopers.application.order.OrderAdminInfo;
import com.loopers.application.order.OrderFacade;
import com.loopers.application.order.OrderInfo;
import com.loopers.application.order.OrderItemInfo;
import com.loopers.application.point.PointFacade;
import com.loopers.application.product.ProductFacade;
import com.loopers.concurrency.ConcurrentRunner.Outcome;
import com.loopers.concurrency.ConcurrentRunner.WorkerResult;
import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderModel;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.point.PointModel;
import com.loopers.domain.product.ProductModel;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.order.OrderJpaRepository;
import com.loopers.infrastructure.point.PointJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

@SpringBootTest
class OrderConcurrencyTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final long USER_ID = 1L;

    @Autowired
    private OrderFacade orderFacade;

    @Autowired
    private PointFacade pointFacade;

    @Autowired
    private ProductFacade productFacade;

    @Autowired
    private BrandJpaRepository brandJpaRepository;

    @Autowired
    private ProductJpaRepository productJpaRepository;

    @Autowired
    private PointJpaRepository pointJpaRepository;

    @Autowired
    private OrderJpaRepository orderJpaRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private BrandModel brand;

    @BeforeEach
    void setUp() {
        brand = brandJpaRepository.save(new BrandModel("나이키"));
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("재고 5에 서로 다른 주문 8건을 동시에 확정하면, 5건만 확정되고 3건은 재고 부족으로 거절되며 재고는 0이 된다.")
    @Test
    void confirmsOnlyUpToStock_whenOrdersCompeteForStock() {
        // arrange: 구매자별로 잔액이 충분하고, 주문은 모두 다르다
        ProductModel product = saveProduct(5, 10_000L);
        List<Long> userIds = new ArrayList<>();
        List<Long> orderIds = new ArrayList<>();
        List<Callable<OrderInfo>> tasks = new ArrayList<>();
        for (long userId = 1; userId <= 8; userId++) {
            saveBalance(userId, 100_000L);
            Long orderId = saveDraft(userId, new OrderItem(product.getId(), 1, 10_000L));
            userIds.add(userId);
            orderIds.add(orderId);
            tasks.add(confirm(orderId, userId));
        }

        // act
        List<WorkerResult<OrderInfo>> results = ConcurrentRunner.runTogether(tasks, TIMEOUT);

        // assert
        List<OrderAdminInfo> orders = orderIds.stream().map(orderFacade::getOrderForAdmin).toList();
        int confirmedQuantity = orders.stream()
            .filter(order -> order.status() == OrderStatus.CONFIRMED)
            .flatMap(order -> order.items().stream())
            .mapToInt(OrderItemInfo::quantity)
            .sum();
        List<Long> expectedBalances = new ArrayList<>();
        List<Long> actualBalances = new ArrayList<>();
        for (int i = 0; i < userIds.size(); i++) {
            boolean confirmed = orders.get(i).status() == OrderStatus.CONFIRMED;
            expectedBalances.add(confirmed ? 90_000L : 100_000L);
            actualBalances.add(balanceOf(userIds.get(i)));
        }
        assertAll(
            () -> assertThat(count(results, Outcome.SUCCESS)).as("확정").isEqualTo(5),
            () -> assertThat(count(results, Outcome.BUSINESS_REJECTED)).as("재고 부족").isEqualTo(3),
            () -> assertThat(technicalErrors(results)).as("기술 오류").isEmpty(),
            () -> assertThat(rejectedTypes(results)).containsOnly(ErrorType.BAD_REQUEST),
            () -> assertThat(stockOf(product)).as("최종 재고").isZero(),
            // 요청 결과와 DB가 같은 이야기를 한다
            () -> assertThat(orders).filteredOn(order -> order.status() == OrderStatus.CONFIRMED).hasSize(5),
            // 수량: 초기 재고 - 성공 주문의 수량 합 = 최종 재고
            () -> assertThat(5 - confirmedQuantity).isEqualTo(stockOf(product)),
            // 잔액: 확정된 구매자만 결제액이 빠진다
            () -> assertThat(actualBalances).isEqualTo(expectedBalances),
            // 집계
            () -> assertThat(total(results)).isEqualTo(8)
        );
    }

    @DisplayName("잔액 10,000원에 4,000원 주문 3건을 동시에 확정하면, 2건만 확정되고 1건은 잔액 부족으로 거절되며 잔액은 2,000원이 된다.")
    @Test
    void confirmsOnlyUpToBalance_whenOrdersCompeteForBalance() {
        // arrange: 재고는 충분하게 둔다
        ProductModel product = saveProduct(100, 4_000L);
        saveBalance(USER_ID, 10_000L);
        List<Long> orderIds = new ArrayList<>();
        List<Callable<OrderInfo>> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Long orderId = saveDraft(USER_ID, new OrderItem(product.getId(), 1, 4_000L));
            orderIds.add(orderId);
            tasks.add(confirm(orderId, USER_ID));
        }

        // act
        List<WorkerResult<OrderInfo>> results = ConcurrentRunner.runTogether(tasks, TIMEOUT);

        // assert
        List<OrderAdminInfo> orders = orderIds.stream().map(orderFacade::getOrderForAdmin).toList();
        long confirmed = orders.stream().filter(order -> order.status() == OrderStatus.CONFIRMED).count();
        assertAll(
            () -> assertThat(count(results, Outcome.SUCCESS)).as("확정").isEqualTo(2),
            () -> assertThat(count(results, Outcome.BUSINESS_REJECTED)).as("잔액 부족").isEqualTo(1),
            () -> assertThat(technicalErrors(results)).as("기술 오류").isEmpty(),
            () -> assertThat(rejectedTypes(results)).containsOnly(ErrorType.CONFLICT),
            () -> assertThat(balanceOf(USER_ID)).as("최종 잔액").isEqualTo(2_000L),
            () -> assertThat(confirmed).isEqualTo(2),
            // 잔액: 초기 잔액 - 성공 결제액 합 = 최종 잔액
            () -> assertThat(10_000L - confirmed * 4_000L).isEqualTo(balanceOf(USER_ID)),
            // 거절된 주문은 재고도 건드리지 않는다
            () -> assertThat(stockOf(product)).as("최종 재고").isEqualTo(98),
            () -> assertThat(total(results)).isEqualTo(3)
        );
    }

    @DisplayName("잔액 10,000원에서 충전 2,000원과 7,000원 주문 확정을 동시에 하면, 둘 다 성공하고 잔액은 5,000원이 된다.")
    @Test
    void bothSucceed_whenChargeAndPaymentRunTogether() {
        // arrange
        ProductModel product = saveProduct(10, 7_000L);
        saveBalance(USER_ID, 10_000L);
        Long orderId = saveDraft(USER_ID, new OrderItem(product.getId(), 1, 7_000L));
        List<Callable<Object>> tasks = List.of(
            () -> pointFacade.charge(USER_ID, 2_000L),
            () -> orderFacade.confirmOrder(orderId, USER_ID)
        );

        // act
        List<WorkerResult<Object>> results = ConcurrentRunner.runTogether(tasks, TIMEOUT);

        // assert
        assertAll(
            () -> assertThat(count(results, Outcome.SUCCESS)).as("성공").isEqualTo(2),
            () -> assertThat(technicalErrors(results)).as("기술 오류").isEmpty(),
            // 잔액: 초기 잔액 + 성공 충전액 - 성공 결제액 = 최종 잔액
            () -> assertThat(balanceOf(USER_ID)).as("최종 잔액").isEqualTo(10_000L + 2_000L - 7_000L),
            () -> assertThat(orderFacade.getOrderForAdmin(orderId).status()).isEqualTo(OrderStatus.CONFIRMED),
            () -> assertThat(stockOf(product)).isEqualTo(9),
            () -> assertThat(total(results)).isEqualTo(2)
        );
    }

    @DisplayName("같은 주문을 동시에 여러 번 확정하면, 한 번만 반영되고 나머지는 404로 거절된다.")
    @Test
    void appliesOnce_whenSameOrderIsConfirmedConcurrently() {
        // arrange
        ProductModel product = saveProduct(5, 10_000L);
        saveBalance(USER_ID, 100_000L);
        Long orderId = saveDraft(USER_ID, new OrderItem(product.getId(), 1, 10_000L));
        List<Callable<OrderInfo>> tasks = List.of(
            confirm(orderId, USER_ID), confirm(orderId, USER_ID), confirm(orderId, USER_ID), confirm(orderId, USER_ID)
        );

        // act
        List<WorkerResult<OrderInfo>> results = ConcurrentRunner.runTogether(tasks, TIMEOUT);

        // assert
        assertAll(
            () -> assertThat(count(results, Outcome.SUCCESS)).as("확정").isEqualTo(1),
            () -> assertThat(count(results, Outcome.BUSINESS_REJECTED)).as("거절").isEqualTo(3),
            () -> assertThat(technicalErrors(results)).as("기술 오류").isEmpty(),
            () -> assertThat(rejectedTypes(results)).containsOnly(ErrorType.NOT_FOUND),
            () -> assertThat(stockOf(product)).as("재고는 한 번만 줄어든다").isEqualTo(4),
            () -> assertThat(balanceOf(USER_ID)).as("잔액은 한 번만 빠진다").isEqualTo(90_000L)
        );
    }

    @DisplayName("관리자의 재고 설정과 주문 확정이 동시에 일어나도, 결과는 둘 중 한 순서로 처리한 값이다.")
    @Test
    void endsWithOneOfTheSerialResults_whenAdminSetsStockWhileOrderIsConfirmed() {
        // arrange
        ProductModel product = saveProduct(5, 10_000L);
        saveBalance(USER_ID, 100_000L);
        Long orderId = saveDraft(USER_ID, new OrderItem(product.getId(), 1, 10_000L));
        List<Callable<Object>> tasks = List.of(
            () -> productFacade.changeStock(product.getId(), 10),
            () -> orderFacade.confirmOrder(orderId, USER_ID)
        );

        // act
        List<WorkerResult<Object>> results = ConcurrentRunner.runTogether(tasks, TIMEOUT);

        // assert: 설정 후 차감이면 9, 차감 후 설정이면 10
        assertAll(
            () -> assertThat(count(results, Outcome.SUCCESS)).as("성공").isEqualTo(2),
            () -> assertThat(technicalErrors(results)).as("기술 오류").isEmpty(),
            () -> assertThat(stockOf(product)).isIn(9, 10)
        );
    }

    @DisplayName("같은 상품 두 개를 서로 반대 순서로 담은 주문들을 동시에 확정해도, 교착 없이 모두 확정된다.")
    @Test
    void confirmsAll_whenOrdersHoldSameProductsInOppositeOrder() {
        // arrange
        ProductModel first = saveProduct(10, 1_000L);
        ProductModel second = saveProduct(10, 1_000L);
        List<Callable<OrderInfo>> tasks = new ArrayList<>();
        for (long userId = 1; userId <= 8; userId++) {
            saveBalance(userId, 100_000L);
            OrderItem a = new OrderItem(first.getId(), 1, 1_000L);
            OrderItem b = new OrderItem(second.getId(), 1, 1_000L);
            Long orderId = userId <= 4 ? saveDraft(userId, a, b) : saveDraft(userId, b, a);
            tasks.add(confirm(orderId, userId));
        }

        // act
        List<WorkerResult<OrderInfo>> results = ConcurrentRunner.runTogether(tasks, TIMEOUT);

        // assert
        assertAll(
            () -> assertThat(count(results, Outcome.SUCCESS)).as("확정").isEqualTo(8),
            () -> assertThat(technicalErrors(results)).as("기술 오류(교착 포함)").isEmpty(),
            () -> assertThat(stockOf(first)).isEqualTo(2),
            () -> assertThat(stockOf(second)).isEqualTo(2)
        );
    }

    private Callable<OrderInfo> confirm(Long orderId, long userId) {
        return () -> orderFacade.confirmOrder(orderId, userId);
    }

    private ProductModel saveProduct(int stock, long price) {
        return productJpaRepository.save(new ProductModel(brand.getId(), "상품", price, stock));
    }

    private void saveBalance(long userId, long balance) {
        PointModel point = new PointModel(userId);
        point.charge(balance);
        pointJpaRepository.save(point);
    }

    private Long saveDraft(long userId, OrderItem... items) {
        return orderJpaRepository.save(new OrderModel(userId, List.of(items))).getId();
    }

    private int stockOf(ProductModel product) {
        return productJpaRepository.findById(product.getId()).orElseThrow().getStock();
    }

    private long balanceOf(long userId) {
        return pointJpaRepository.findByUserId(userId).orElseThrow().getBalance();
    }

    private static long count(List<? extends WorkerResult<?>> results, Outcome outcome) {
        return ConcurrentRunner.count(results, outcome);
    }

    private static long total(List<? extends WorkerResult<?>> results) {
        return count(results, Outcome.SUCCESS)
            + count(results, Outcome.BUSINESS_REJECTED)
            + count(results, Outcome.TECHNICAL_ERROR);
    }

    private static List<String> technicalErrors(List<? extends WorkerResult<?>> results) {
        return results.stream()
            .filter(result -> result.outcome() == Outcome.TECHNICAL_ERROR)
            .map(result -> String.valueOf(result.error()))
            .toList();
    }

    private static List<ErrorType> rejectedTypes(List<? extends WorkerResult<?>> results) {
        return results.stream()
            .filter(result -> result.outcome() == Outcome.BUSINESS_REJECTED)
            .map(result -> ((CoreException) result.error()).getErrorType())
            .toList();
    }
}
