package com.loopers.order.application;

import com.loopers.support.fixture.TestEntities;

import com.loopers.brand.domain.Brand;
import com.loopers.order.domain.Order;
import com.loopers.order.domain.OrderStatus;
import com.loopers.product.domain.Product;
import com.loopers.support.fixture.CommerceFixture;
import com.loopers.testcontainers.MySqlTestContainersConfig;
import com.loopers.user.domain.User;
import com.loopers.user.domain.Point;
import com.loopers.user.infrastructure.PointRepositoryAdapter;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest
@Import(MySqlTestContainersConfig.class)
class OrderTransactionTest {

    @Autowired private OrderUseCase useCase;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private DatabaseCleanUp databaseCleanUp;
    @MockitoSpyBean private PointRepositoryAdapter pointRepository;

    private CommerceFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new CommerceFixture(entityManager, transactionManager);
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
        fixture.truncateRemainingTables();
    }

    @DisplayName("[R-ORDER-11][R-ORDER-12] 여러 품목의 주문 확정 결과가 함께 저장된다.")
    @Test
    void commitsOrderStockPointAndPaymentTogether() {
        Brand brand = fixture.brand("Nike");
        Product first = fixture.product(brand, "Air", 2_000L, 5);
        Product second = fixture.product(brand, "Dunk", 3_000L, 4);
        User buyer = fixture.userWithPoint(10_000L);
        Order order = fixture.draftOrder(buyer, fixture.item(first, 2), fixture.item(second, 1));

        useCase.confirm(buyer.getId(), order.getId());

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Order savedOrder = entityManager.find(Order.class, order.getId());
            assertAll(
                () -> assertThat(savedOrder.getStatus()).isEqualTo(OrderStatus.CONFIRMED),
                () -> assertThat(savedOrder.getTotalAmount()).isEqualTo(7_000L),
                () -> assertThat(savedOrder.getPaymentResult()).isNotNull(),
                () -> assertThat(savedOrder.getPaymentResult().amount()).isEqualTo(7_000L),
                () -> assertThat(savedOrder.getPaymentResult().paidAt()).isNotNull(),
                () -> assertThat(TestEntities.stockQuantity(entityManager, first.getId()))
                    .isEqualTo(3),
                () -> assertThat(TestEntities.stockQuantity(entityManager, second.getId()))
                    .isEqualTo(3),
                () -> assertThat(TestEntities.pointBalance(entityManager, buyer.getId()))
                    .isEqualTo(3_000L)
            );
        });
    }

    @DisplayName("[INV-ORDER-35] 재고 차감 SQL 전송 뒤 저장에 실패하면 주문·재고·잔액·결제 결과를 되돌린다.")
    @Test
    void rollsBackConfirmationAfterFlushedChanges() {
        Brand brand = fixture.brand("Nike");
        Product first = fixture.product(brand, "Air", 2_000L, 5);
        Product second = fixture.product(brand, "Dunk", 3_000L, 4);
        User buyer = fixture.userWithPoint(10_000L);
        Order order = fixture.draftOrder(buyer, fixture.item(first, 2), fixture.item(second, 1));
        AtomicBoolean flushed = new AtomicBoolean();

        doAnswer(invocation -> {
            entityManager.flush();
            assertAll(
                () -> assertThat(numberFrom("select quantity from stock where product_id = :id", first.getId()))
                    .isEqualTo(3),
                () -> assertThat(numberFrom("select quantity from stock where product_id = :id", second.getId()))
                    .isEqualTo(3),
                () -> assertThat(numberFrom("select balance from point where user_id = :id", buyer.getId()))
                    .isEqualTo(3_000L),
                () -> assertThat(numberFrom("select amount from orders where id = :id", order.getId()))
                    .isEqualTo(7_000L)
            );
            flushed.set(true);
            throw new IllegalStateException("injected user save failure");
        }).when(pointRepository).save(any(Point.class));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> useCase.confirm(buyer.getId(), order.getId()));

        assertAll(
            () -> assertThat(failure).hasMessage("injected user save failure"),
            () -> assertThat(flushed).isTrue()
        );
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> assertAll(
            () -> assertThat(entityManager.find(Order.class, order.getId()).getStatus())
                .isEqualTo(OrderStatus.DRAFT),
            () -> assertThat(entityManager.find(Order.class, order.getId()).getPaymentResult()).isNull(),
            () -> assertThat(TestEntities.stockQuantity(entityManager, first.getId()))
                .isEqualTo(5),
            () -> assertThat(TestEntities.stockQuantity(entityManager, second.getId()))
                .isEqualTo(4),
            () -> assertThat(TestEntities.pointBalance(entityManager, buyer.getId()))
                .isEqualTo(10_000L)
        ));
    }

    private long numberFrom(String sql, Long id) {
        return ((Number) entityManager.createNativeQuery(sql)
            .setParameter("id", id)
            .getSingleResult()).longValue();
    }
}
