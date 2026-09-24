package com.loopers.application.ordering.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

import com.loopers.application.mall.product.ProductCommand;
import com.loopers.application.mall.product.SetProductStockUseCase;
import com.loopers.application.pay.wallet.ChargeWalletUseCase;
import com.loopers.application.pay.wallet.WalletCommand;
import com.loopers.domain.mall.brand.Brand;
import com.loopers.domain.mall.brand.BrandRepository;
import com.loopers.domain.mall.product.Product;
import com.loopers.domain.mall.product.ProductRepository;
import com.loopers.domain.ordering.order.Order;
import com.loopers.domain.ordering.order.OrderItem;
import com.loopers.domain.ordering.order.OrderRepository;
import com.loopers.domain.pay.wallet.Wallet;
import com.loopers.domain.pay.wallet.WalletRepository;
import com.loopers.domain.shared.Money;
import com.loopers.domain.shopping.user.User;
import com.loopers.domain.shopping.user.UserRepository;
import com.loopers.domain.support.error.DomainErrorCode;
import com.loopers.domain.support.error.DomainException;
import com.loopers.support.concurrency.ConcurrentRequests;
import com.loopers.support.concurrency.ConcurrentRequests.Outcome;
import com.loopers.utils.DatabaseCleanUp;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
// 실제 Spring 서비스로 필수 경쟁 시나리오를 검증(requirement.md 완료 조건 + 잠금 순서)
class ConfirmOrderConcurrencyIntegrationTest {
    @Autowired
    private ConfirmOrderUseCase confirmOrderUseCase;
    @Autowired
    private ChargeWalletUseCase chargeWalletUseCase;
    @Autowired
    private SetProductStockUseCase setProductStockUseCase;
    @Autowired
    private BrandRepository brandRepository;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private WalletRepository walletRepository;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private JdbcClient jdbcClient;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private final AtomicLong nextUserId = new AtomicLong(1);

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("같은 주문을 동시에 두 번 확정하면 한 번만 성공하고 나머지는 기존 상태 오류로 거절된다")
    @Test
    void concurrentReconfirm_succeedsOnce() throws Exception {
        long productId = createProduct(5);
        long userId = createUserWithWallet(10_000L);
        Order order = createOrder(userId, List.of(OrderItem.create(productId, "상품", 1_000L, 1)));

        List<Callable<Object>> tasks = List.of(
            () -> confirmOrderUseCase.execute(new ConfirmOrderCommand(order.getId())),
            () -> confirmOrderUseCase.execute(new ConfirmOrderCommand(order.getId()))
        );
        List<Outcome<Object>> results = ConcurrentRequests.run(tasks, 10, 30);

        assertAll(
            () -> assertThat(successCount(results)).isEqualTo(1),
            () -> assertThat(errorCount(results, DomainErrorCode.ORDER_ALREADY_CONFIRMED)).isEqualTo(1),
            () -> assertThat(technicalErrorCount(results, Set.of(DomainErrorCode.ORDER_ALREADY_CONFIRMED))).isZero(),
            () -> assertThat(productRepository.findById(productId).orElseThrow().getStock()).isEqualTo(4),
            () -> assertThat(walletRepository.findByUserId(userId).orElseThrow().getBalance()).isEqualTo(9_000L),
            () -> assertThat(countUsePointBills(userId, order.getId())).isEqualTo(1L),
            () -> assertThat(countPaidOrderBills(order.getId())).isEqualTo(1L)
        );
        assertOrderOutcome(order, true);
    }

    @DisplayName("재고 5에 서로 다른 구매자 8명이 동시에 1개씩 주문하면 재고만큼만 성공한다")
    @Test
    void concurrentStockRace_acceptsUpToAvailableStock() throws Exception {
        long productId = createProduct(5);
        List<Order> orders = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            long userId = createUserWithWallet(10_000L);
            orders.add(createOrder(userId, List.of(OrderItem.create(productId, "상품", 1_000L, 1))));
        }

        List<Callable<Object>> tasks = orders.stream()
            .<Callable<Object>>map(order -> () -> confirmOrderUseCase.execute(new ConfirmOrderCommand(order.getId())))
            .toList();
        List<Outcome<Object>> results = ConcurrentRequests.run(tasks, 10, 30);

        assertAll(
            () -> assertThat(successCount(results)).isEqualTo(5),
            () -> assertThat(errorCount(results, DomainErrorCode.INSUFFICIENT_STOCK)).isEqualTo(3),
            () -> assertThat(technicalErrorCount(results, Set.of(DomainErrorCode.INSUFFICIENT_STOCK))).isZero(),
            () -> assertThat(productRepository.findById(productId).orElseThrow().getStock()).isZero()
        );
        assertThat(results).hasSize(orders.size());
        for (int i = 0; i < orders.size(); i++) {
            Order order = orders.get(i);
            boolean succeeded = results.get(i).isSuccess();
            assertOrderOutcome(order, succeeded);
            assertThat(walletRepository.findByUserId(order.getUserId()).orElseThrow().getBalance())
                .isEqualTo(succeeded ? 9_000L : 10_000L);
        }
    }

    @DisplayName("한 사용자가 4,000원 주문 3건을 동시에 확정하면 잔액이 허용하는 만큼만 성공한다")
    @Test
    void concurrentBalanceRace_acceptsUpToAvailableBalance() throws Exception {
        long userId = createUserWithWallet(10_000L);
        List<Order> orders = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            long productId = createProduct(10);
            orders.add(createOrder(userId, List.of(OrderItem.create(productId, "상품", 4_000L, 1))));
        }

        List<Callable<Object>> tasks = orders.stream()
            .<Callable<Object>>map(order -> () -> confirmOrderUseCase.execute(new ConfirmOrderCommand(order.getId())))
            .toList();
        List<Outcome<Object>> results = ConcurrentRequests.run(tasks, 10, 30);

        assertAll(
            () -> assertThat(successCount(results)).isEqualTo(2),
            () -> assertThat(errorCount(results, DomainErrorCode.INSUFFICIENT_POINT)).isEqualTo(1),
            () -> assertThat(technicalErrorCount(results, Set.of(DomainErrorCode.INSUFFICIENT_POINT))).isZero(),
            () -> assertThat(walletRepository.findByUserId(userId).orElseThrow().getBalance()).isEqualTo(2_000L)
        );
        assertThat(results).hasSize(orders.size());
        for (int i = 0; i < orders.size(); i++) {
            Order order = orders.get(i);
            boolean succeeded = results.get(i).isSuccess();
            assertOrderOutcome(order, succeeded);
            assertThat(productRepository.findById(order.getItems().get(0).getProductId()).orElseThrow().getStock())
                .isEqualTo(succeeded ? 9 : 10);
        }
    }

    @DisplayName("충전과 결제를 동시에 실행하면 둘 다 성공하고 최종 잔액이 정확하다")
    @Test
    void concurrentChargeAndConfirm_bothSucceed() throws Exception {
        long userId = createUserWithWallet(10_000L);
        long productId = createProduct(5);
        Order order = createOrder(userId, List.of(OrderItem.create(productId, "상품", 7_000L, 1)));

        List<Callable<Object>> tasks = List.of(
            () -> confirmOrderUseCase.execute(new ConfirmOrderCommand(order.getId())),
            () -> chargeWalletUseCase.execute(new WalletCommand.Charge(userId, 2_000L))
        );
        List<Outcome<Object>> results = ConcurrentRequests.run(tasks, 10, 30);

        assertAll(
            () -> assertThat(successCount(results)).isEqualTo(2),
            () -> assertThat(technicalErrorCount(results, Set.of())).isZero(),
            () -> assertThat(walletRepository.findByUserId(userId).orElseThrow().getBalance()).isEqualTo(5_000L),
            () -> assertThat(countUsePointBills(userId, order.getId())).isEqualTo(1L)
        );
        assertOrderOutcome(order, true);
        assertThat(productRepository.findById(productId).orElseThrow().getStock()).isEqualTo(4);
        assertThat(jdbcClient.sql("SELECT amount FROM point_bills WHERE user_id = :userId AND type = 'CHARGE'")
            .param("userId", userId).query(Long.class).list()).containsExactly(2_000L);
    }

    @DisplayName("관리자 재고 설정과 주문 확정을 동시에 실행해도 순차 실행에 해당하는 결과만 나온다")
    @Test
    void concurrentSetStockAndConfirm_matchesSequentialOutcome() throws Exception {
        long productId = createProduct(5);
        long userId = createUserWithWallet(10_000L);
        Order order = createOrder(userId, List.of(OrderItem.create(productId, "상품", 1_000L, 2)));

        List<Callable<Object>> tasks = List.of(
            () -> confirmOrderUseCase.execute(new ConfirmOrderCommand(order.getId())),
            () -> setProductStockUseCase.execute(new ProductCommand.SetStock(productId, 10))
        );
        List<Outcome<Object>> results = ConcurrentRequests.run(tasks, 10, 30);

        int finalStock = productRepository.findById(productId).orElseThrow().getStock();
        assertAll(
            () -> assertThat(successCount(results)).isEqualTo(2),
            () -> assertThat(technicalErrorCount(results, Set.of())).isZero(),
            () -> assertThat(finalStock).isIn(8, 10)
        );
        assertOrderOutcome(order, true);
        assertThat(walletRepository.findByUserId(userId).orElseThrow().getBalance()).isEqualTo(8_000L);
    }

    @DisplayName("같은 두 상품을 반대 순서로 담은 두 주문이 동시에 확정돼도 데드락 없이 둘 다 성공한다")
    @Test
    void concurrentReversedItemOrder_bothSucceedWithoutDeadlock() throws Exception {
        long productAId = createProduct(5);
        long productBId = createProduct(5);
        long user1 = createUserWithWallet(10_000L);
        long user2 = createUserWithWallet(10_000L);
        Order order1 = createOrder(user1, List.of(
            OrderItem.create(productAId, "상품A", 1_000L, 1),
            OrderItem.create(productBId, "상품B", 1_000L, 1)
        ));
        Order order2 = createOrder(user2, List.of(
            OrderItem.create(productBId, "상품B", 1_000L, 1),
            OrderItem.create(productAId, "상품A", 1_000L, 1)
        ));

        List<Callable<Object>> tasks = List.of(
            () -> confirmOrderUseCase.execute(new ConfirmOrderCommand(order1.getId())),
            () -> confirmOrderUseCase.execute(new ConfirmOrderCommand(order2.getId()))
        );
        List<Outcome<Object>> results = ConcurrentRequests.run(tasks, 10, 30);

        assertAll(
            () -> assertThat(successCount(results)).isEqualTo(2),
            () -> assertThat(technicalErrorCount(results, Set.of())).isZero(),
            () -> assertThat(productRepository.findById(productAId).orElseThrow().getStock()).isEqualTo(3),
            () -> assertThat(productRepository.findById(productBId).orElseThrow().getStock()).isEqualTo(3)
        );
        assertOrderOutcome(order1, true);
        assertOrderOutcome(order2, true);
        assertThat(walletRepository.findByUserId(user1).orElseThrow().getBalance()).isEqualTo(8_000L);
        assertThat(walletRepository.findByUserId(user2).orElseThrow().getBalance()).isEqualTo(8_000L);
    }

    private void assertOrderOutcome(Order order, boolean succeeded) {
        assertAll(
            () -> assertThat(jdbcClient.sql("SELECT status FROM orders WHERE id = :orderId")
                .param("orderId", order.getId()).query(String.class).single())
                .isEqualTo(succeeded ? "CONFIRMED" : "DRAFT"),
            () -> assertThat(jdbcClient.sql("SELECT total_amount FROM orders WHERE id = :orderId")
                .param("orderId", order.getId()).query(Long.class).single()).isEqualTo(order.getTotalAmount()),
            () -> assertThat(jdbcClient.sql("SELECT SUM(quantity) FROM order_items WHERE order_id = :orderId")
                .param("orderId", order.getId()).query(Long.class).single())
                .isEqualTo(order.getItems().stream().mapToLong(OrderItem::getQuantity).sum()),
            () -> assertThat(countUsePointBills(order.getUserId(), order.getId())).isEqualTo(succeeded ? 1 : 0),
            () -> assertThat(countPaidOrderBills(order.getId())).isEqualTo(succeeded ? 1 : 0),
            () -> assertThat(jdbcClient.sql("SELECT COALESCE(SUM(amount), 0) FROM point_bills WHERE order_id = :orderId")
                .param("orderId", order.getId()).query(Long.class).single()).isEqualTo(succeeded ? order.getTotalAmount() : 0),
            () -> assertThat(jdbcClient.sql("SELECT COALESCE(SUM(amount), 0) FROM order_bills WHERE order_id = :orderId")
                .param("orderId", order.getId()).query(Long.class).single()).isEqualTo(succeeded ? order.getTotalAmount() : 0)
        );
    }

    private long successCount(List<Outcome<Object>> results) {
        return results.stream().filter(Outcome::isSuccess).count();
    }

    private long errorCount(List<Outcome<Object>> results, DomainErrorCode code) {
        return results.stream()
            .filter(result -> !result.isSuccess())
            .filter(result -> result.error() instanceof DomainException domainException
                && domainException.getErrorCode() == code)
            .count();
    }

    // 예상한 업무 거절 코드 이외의 실패(기술 오류)가 있으면 0보다 커진다
    private long technicalErrorCount(List<Outcome<Object>> results, Set<DomainErrorCode> expectedRejections) {
        return results.stream()
            .filter(result -> !result.isSuccess())
            .filter(result -> !(result.error() instanceof DomainException domainException
                && expectedRejections.contains(domainException.getErrorCode())))
            .count();
    }

    private long createUserWithWallet(long balance) {
        long userId = nextUserId.getAndIncrement();
        userRepository.save(User.create(userId));
        Wallet wallet = walletRepository.save(Wallet.zero(userId));
        wallet.charge(Money.positive(balance));
        walletRepository.save(wallet);
        return userId;
    }

    private long createProduct(int stock) {
        Brand brand = brandRepository.save(Brand.create("브랜드", null));
        return productRepository.save(Product.create(brand.getId(), "상품", null, 1_000L, stock)).getId();
    }

    private Order createOrder(long userId, List<OrderItem> items) {
        return orderRepository.save(Order.create(userId, items));
    }

    private long countUsePointBills(long userId, long orderId) {
        return jdbcClient.sql(
                "SELECT COUNT(*) FROM point_bills WHERE user_id = :userId AND type = 'USE' AND order_id = :orderId")
            .param("userId", userId)
            .param("orderId", orderId)
            .query(Long.class)
            .single();
    }

    private long countPaidOrderBills(long orderId) {
        return jdbcClient.sql("SELECT COUNT(*) FROM order_bills WHERE order_id = :orderId AND status = 'PAID'")
            .param("orderId", orderId)
            .query(Long.class)
            .single();
    }
}
