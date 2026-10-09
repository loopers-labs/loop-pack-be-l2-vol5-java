package com.loopers.application.order;

import com.loopers.application.user.UserRegistrationService;
import com.loopers.application.point.PointFacade;
import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.point.PointBalanceModel;
import com.loopers.domain.user.UserModel;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.order.OrderJpaRepository;
import com.loopers.infrastructure.point.PointBalanceJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class OrderPersistenceIntegrationTest {

    @Autowired
    private UserRegistrationService registration;
    @Autowired
    private OrderFacade orderFacade;
    @Autowired
    private PointFacade pointFacade;
    @Autowired
    private UserJpaRepository userJpaRepository;
    @Autowired
    private BrandJpaRepository brandJpaRepository;
    @Autowired
    private ProductJpaRepository productJpaRepository;
    @Autowired
    private OrderJpaRepository orderJpaRepository;
    @Autowired
    private PointBalanceJpaRepository pointBalanceJpaRepository;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @Test
    void persistsConfirmedOrderStockAndPointBalanceAfterFlushAndClear() {
        UserModel user = registration.register();
        BrandModel brand = brandJpaRepository.save(new BrandModel("나이키", null));
        var product = productJpaRepository.save(new com.loopers.domain.product.ProductModel(
            brand.getId(), "운동화", 2_000L, 5
        ));
        pointFacade.charge(user.getId(), 10_000L);

        OrderInfo draft = orderFacade.create(
            user.getId(),
            List.of(new OrderFacade.OrderLine(product.getId(), 2))
        );
        OrderInfo confirmed = orderFacade.confirm(user.getId(), draft.id());

        transactionTemplate.executeWithoutResult(status -> {
            entityManager.flush();
            entityManager.clear();

            var storedOrder = orderJpaRepository.findById(confirmed.id()).orElseThrow();
            var storedProduct = productJpaRepository.findById(product.getId()).orElseThrow();
            var storedBalance = pointBalanceJpaRepository.findByUserId(user.getId()).orElseThrow();

            assertThat(storedOrder.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(storedOrder.getPaidAmount()).isEqualTo(4_000L);
            assertThat(storedOrder.getItems()).singleElement().satisfies(item -> {
                assertThat(item.getProductName()).isEqualTo("운동화");
                assertThat(item.getUnitPrice()).isEqualTo(2_000L);
                assertThat(item.getQuantity()).isEqualTo(2);
            });
            assertThat(storedProduct.getStockQuantity()).isEqualTo(3);
            assertThat(storedBalance.getBalance()).isEqualTo(6_080L);
        });
    }

    @Test
    void rollsBackStockPointExpirationAndOrderWhenExpiredPointMakesPaymentInsufficient() {
        UserModel user = registration.register();
        BrandModel brand = brandJpaRepository.save(new BrandModel("아식스", null));
        var product = productJpaRepository.save(new com.loopers.domain.product.ProductModel(
            brand.getId(), "러닝화", 2_000L, 5
        ));
        transactionTemplate.executeWithoutResult(status -> {
            PointBalanceModel balance = pointBalanceJpaRepository.findByUserId(user.getId()).orElseThrow();
            balance.charge(1_000L);
            balance.reward(1_000L, ZonedDateTime.now().minusYears(1).minusDays(1));
            pointBalanceJpaRepository.save(balance);
        });
        OrderInfo draft = orderFacade.create(
            user.getId(),
            List.of(new OrderFacade.OrderLine(product.getId(), 1))
        );

        assertThatThrownBy(() -> orderFacade.confirm(user.getId(), draft.id()))
            .isInstanceOf(com.loopers.support.error.CoreException.class);

        transactionTemplate.executeWithoutResult(status -> {
            entityManager.flush();
            entityManager.clear();

            assertThat(orderJpaRepository.findById(draft.id()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.DRAFT);
            assertThat(productJpaRepository.findById(product.getId()).orElseThrow().getStockQuantity())
                .isEqualTo(5);
            assertThat(pointBalanceJpaRepository.findByUserId(user.getId()).orElseThrow().getBalance())
                .isEqualTo(2_000L);
        });
    }
}
