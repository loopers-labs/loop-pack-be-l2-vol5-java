package com.loopers.application.order;

import com.loopers.application.point.PointFacade;
import com.loopers.application.user.UserRegistrationService;
import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.point.PointBalanceService;
import com.loopers.domain.product.ProductModel;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.order.OrderJpaRepository;
import com.loopers.infrastructure.point.PointBalanceJpaRepository;
import com.loopers.infrastructure.point.PointBalanceRepositoryImpl;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.infrastructure.product.ProductRepositoryImpl;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;

@SpringBootTest
class TransactionFailureIntegrationTest {
    @Autowired private UserRegistrationService registration;
    @Autowired private OrderFacade orders;
    @Autowired private PointFacade points;
    @Autowired private BrandJpaRepository brands;
    @Autowired private ProductJpaRepository products;
    @Autowired private OrderJpaRepository orderRepository;
    @Autowired private PointBalanceJpaRepository balances;
    @Autowired private EntityManager entityManager;
    @Autowired private TransactionTemplate transaction;
    @Autowired private DatabaseCleanUp cleanUp;
    @MockitoSpyBean private PointBalanceService pointService;
    @MockitoSpyBean private ProductRepositoryImpl productPersistence;
    @MockitoSpyBean private PointBalanceRepositoryImpl pointPersistence;

    @AfterEach
    void clean() {
        reset(pointService, productPersistence, pointPersistence);
        cleanUp.truncateAllTables();
    }

    @Test
    void rollsBackStockSqlWhenFailureOccursBeforePointPayment() {
        Purchase purchase = purchase(1);
        doAnswer(invocation -> {
            entityManager.flush();
            assertStockSql(purchase.productIds().getFirst(), 4);
            assertThat(number("select count(*) from point_usage")).isZero();
            throw new IllegalStateException("failure before point payment");
        }).when(pointService).use(purchase.userId(), 1_000L);

        assertThatThrownBy(() -> orders.confirm(purchase.userId(), purchase.orderId()))
            .isInstanceOf(IllegalStateException.class).hasMessage("failure before point payment");

        assertRolledBack(purchase);
    }

    @Test
    void rollsBackFirstProductSqlWhenSecondProductProcessingFails() {
        Purchase purchase = purchase(2);
        long secondId = purchase.productIds().get(1);
        doAnswer(invocation -> {
            Optional<?> actual = (Optional<?>) invocation.callRealMethod();
            ProductModel failingProduct = spy((ProductModel) actual.orElseThrow());
            doAnswer(decrease -> {
                entityManager.flush();
                assertStockSql(purchase.productIds().getFirst(), 4);
                assertStockSql(secondId, 5);
                throw new IllegalStateException("failure at second product");
            }).when(failingProduct).decreaseStock(1);
            return Optional.of(failingProduct);
        }).when(productPersistence).findForUpdate(secondId);

        assertThatThrownBy(() -> orders.confirm(purchase.userId(), purchase.orderId()))
            .isInstanceOf(IllegalStateException.class).hasMessage("failure at second product");

        assertRolledBack(purchase);
    }

    @Test
    void rollsBackBothStockUpdatesAndPointPaymentSqlBeforeReward() {
        Purchase purchase = purchase(2);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            entityManager.flush();
            purchase.productIds().forEach(id -> assertStockSql(id, 4));
            assertThat(number("select balance from point_balance")).isEqualTo(8_000L);
            assertThat(number("select sum(amount) from point_usage where type = 'PAYMENT'"))
                .isEqualTo(2_000L);
            assertThat(number("select count(*) from point_grant where type = 'REWARD'")).isZero();
            throw new IllegalStateException("failure after point payment SQL");
        }).when(pointService).use(purchase.userId(), 2_000L);

        assertThatThrownBy(() -> orders.confirm(purchase.userId(), purchase.orderId()))
            .isInstanceOf(IllegalStateException.class).hasMessage("failure after point payment SQL");

        assertRolledBack(purchase);
    }

    @Test
    void chargePersistsGrantAndBalanceTogether() {
        long userId = registration.register().getId();

        assertThat(points.charge(userId, 2_000L).balance()).isEqualTo(2_000L);

        transaction.executeWithoutResult(status -> {
            entityManager.flush();
            entityManager.clear();
            assertThat(balances.findByUserId(userId).orElseThrow().getBalance()).isEqualTo(2_000L);
            assertThat(number("select sum(amount) from point_grant where type = 'CHARGE'"))
                .isEqualTo(2_000L);
            assertThat(number("select count(*) from point_grant")).isEqualTo(1);
            assertThat(number("select count(*) from point_usage")).isZero();
        });
    }

    @Test
    void chargeFailureAfterSqlRollsBackChargeAndExpirationTogether() {
        long userId = registration.register().getId();
        transaction.executeWithoutResult(status -> {
            var balance = balances.findByUserId(userId).orElseThrow();
            balance.charge(700L);
            balance.reward(300L, ZonedDateTime.now().minusYears(2));
            balances.save(balance);
        });
        doAnswer(invocation -> {
            invocation.callRealMethod();
            entityManager.flush();
            assertThat(number("select balance from point_balance")).isEqualTo(2_700L);
            assertThat(number("select sum(amount) from point_usage where type = 'EXPIRATION'"))
                .isEqualTo(300L);
            assertThat(number("select sum(amount) from point_grant where type = 'CHARGE'"))
                .isEqualTo(2_700L);
            throw new IllegalStateException("failure after charge and expiration SQL");
        }).when(pointPersistence).save(any());

        assertThatThrownBy(() -> points.charge(userId, 2_000L))
            .isInstanceOf(IllegalStateException.class).hasMessage("failure after charge and expiration SQL");

        transaction.executeWithoutResult(status -> {
            entityManager.clear();
            assertThat(balances.findByUserId(userId).orElseThrow().getBalance()).isEqualTo(1_000L);
            assertThat(number("select count(*) from point_grant")).isEqualTo(2);
            assertThat(number("select sum(amount) from point_grant where type = 'CHARGE'"))
                .isEqualTo(700L);
            assertThat(number("select count(*) from point_usage")).isZero();
        });
    }

    private Purchase purchase(int productCount) {
        long userId = registration.register().getId();
        points.charge(userId, 10_000L);
        long brandId = brands.save(new BrandModel("brand", "description")).getId();
        List<Long> ids = java.util.stream.IntStream.range(0, productCount)
            .mapToObj(index -> products.save(new ProductModel(brandId, "product" + index, 1_000L, 5)).getId())
            .toList();
        long orderId = orders.create(userId, ids.stream().map(id -> new OrderFacade.OrderLine(id, 1)).toList()).id();
        return new Purchase(userId, orderId, ids);
    }

    private void assertRolledBack(Purchase purchase) {
        transaction.executeWithoutResult(status -> {
            entityManager.clear();
            purchase.productIds().forEach(id -> assertStockSql(id, 5));
            assertThat(balances.findByUserId(purchase.userId()).orElseThrow().getBalance()).isEqualTo(10_000L);
            var order = orderRepository.findById(purchase.orderId()).orElseThrow();
            assertThat(order.getStatus()).isEqualTo(OrderStatus.DRAFT);
            assertThat(order.getPaidAmount()).isZero();
            assertThat(number("select count(*) from point_usage")).isZero();
            assertThat(number("select count(*) from point_grant")).isEqualTo(1);
        });
    }

    private void assertStockSql(long productId, int expected) {
        assertThat(number("select quantity from product where id = " + productId)).isEqualTo(expected);
    }

    private long number(String sql) {
        return ((Number) entityManager.createNativeQuery(sql).getSingleResult()).longValue();
    }

    private record Purchase(long userId, long orderId, List<Long> productIds) {}
}
