package com.loopers.application.ordering.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

import com.loopers.domain.mall.brand.Brand;
import com.loopers.domain.mall.brand.BrandRepository;
import com.loopers.domain.mall.product.Product;
import com.loopers.domain.mall.product.ProductRepository;
import com.loopers.domain.ordering.order.Order;
import com.loopers.domain.ordering.order.OrderItem;
import com.loopers.domain.ordering.order.OrderRepository;
import com.loopers.domain.pay.wallet.PointBill;
import com.loopers.domain.pay.wallet.PointBillRepository;
import com.loopers.domain.pay.wallet.Wallet;
import com.loopers.domain.pay.wallet.WalletRepository;
import com.loopers.domain.shared.Money;
import com.loopers.domain.shopping.user.User;
import com.loopers.domain.shopping.user.UserRepository;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@SpringBootTest
// 저장 전 검증 실패와 구분되는, 실제 변경 SQL 실행 이후 전체 롤백을 검증
class ConfirmOrderSqlRollbackIntegrationTest {
    @Autowired
    private ConfirmOrderUseCase confirmOrderUseCase;
    @Autowired
    private BrandRepository brandRepository;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private WalletRepository walletRepository;
    @Autowired
    private PointBillRepository pointBillRepository;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private JdbcClient jdbcClient;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;
    @MockitoSpyBean
    private OrderRepository orderRepository;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("재고·잔액·기록까지 실제 SQL로 반영된 뒤 마지막 저장 단계에서 실패해도 확정 전체가 롤백된다")
    @Test
    void rollsBackEverything_afterRealChangeSqlAlreadyApplied() {
        // arrange: 여러 품목, 기존 거래 기록(충전), 무관한 다른 주문을 먼저 커밋
        long productAId = createProduct(5);
        long productBId = createProduct(5);
        long unrelatedProductId = createProduct(5);
        userRepository.save(User.create(1L));
        Wallet wallet = walletRepository.save(Wallet.zero(1L));
        PointBill chargeBill = wallet.charge(Money.positive(10_000L));
        walletRepository.save(wallet);
        pointBillRepository.save(chargeBill);

        Order unrelatedOrder = createOrder(1L, List.of(OrderItem.create(unrelatedProductId, "상품", 1_000L, 1)));
        Order order = createOrder(1L, List.of(
            OrderItem.create(productAId, "상품", 1_000L, 2),
            OrderItem.create(productBId, "상품", 1_000L, 1)
        ));

        AtomicInteger stockObservedDuringSave = new AtomicInteger(-1);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            entityManager.flush();
            Number stock = (Number) entityManager.createNativeQuery("SELECT stock FROM products WHERE id = ?1")
                .setParameter(1, productAId)
                .getSingleResult();
            stockObservedDuringSave.set(stock.intValue());
            throw new IllegalStateException("forced failure after real save SQL applied");
        }).when(orderRepository).save(any());

        try {
            // act & assert
            assertThatThrownBy(() -> confirmOrderUseCase.execute(new ConfirmOrderCommand(order.getId())))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("forced failure after real save SQL applied");

            assertAll(
                () -> assertThat(stockObservedDuringSave.get())
                    .as("트랜잭션 안에서 상품A 재고 차감 SQL이 실제로 반영됐어야 한다(5-2=3)")
                    .isEqualTo(3),
                () -> assertThat(productRepository.findById(productAId).orElseThrow().getStock()).isEqualTo(5),
                () -> assertThat(productRepository.findById(productBId).orElseThrow().getStock()).isEqualTo(5),
                () -> assertThat(walletRepository.findByUserId(1L).orElseThrow().getBalance()).isEqualTo(10_000L),
                () -> assertThat(orderStatus(order.getId())).isEqualTo("DRAFT"),
                () -> assertThat(countUsePointBills(1L, order.getId())).isZero(),
                () -> assertThat(countPaidOrderBills(order.getId())).isZero(),
                () -> assertThat(orderStatus(unrelatedOrder.getId())).isEqualTo("DRAFT"),
                () -> assertThat(productRepository.findById(unrelatedProductId).orElseThrow().getStock()).isEqualTo(5),
                () -> assertThat(jdbcClient.sql("SELECT amount FROM point_bills WHERE user_id = 1 AND type = 'CHARGE'")
                    .query(Long.class).list()).containsExactly(10_000L)
            );
        } finally {
            reset(orderRepository);
        }
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

    private String orderStatus(long orderId) {
        return jdbcClient.sql("SELECT status FROM orders WHERE id = :orderId")
            .param("orderId", orderId)
            .query(String.class)
            .single();
    }
}
