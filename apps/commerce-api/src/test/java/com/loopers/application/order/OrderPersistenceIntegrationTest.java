package com.loopers.application.order;

import com.loopers.application.user.UserRegistrationService;
import com.loopers.application.point.PointFacade;
import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.order.PaymentMethod;
import com.loopers.domain.order.ReservationStatus;
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

import static org.assertj.core.api.Assertions.assertThat;

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
    void persistsPgPendingOrderReservedStockAndUnchangedPointsAfterFlushAndClear() {
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
        OrderPaymentInfo started = orderFacade.confirm(user.getId(), draft.id(),
            new OrderFacade.PaymentCommand("attempt-1", "TEST", "0000-0000-0000-0000"));

        transactionTemplate.executeWithoutResult(status -> {
            entityManager.flush();
            entityManager.clear();

            var storedOrder = orderJpaRepository.findById(started.order().id()).orElseThrow();
            var storedProduct = productJpaRepository.findById(product.getId()).orElseThrow();
            var storedBalance = pointBalanceJpaRepository.findByUserId(user.getId()).orElseThrow();

            assertThat(storedOrder.getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
            assertThat(storedOrder.getPaymentMethod()).isEqualTo(PaymentMethod.PG);
            assertThat(storedOrder.getReservationStatus()).isEqualTo(ReservationStatus.RESERVED);
            assertThat(storedOrder.getPaidAmount()).isZero();
            assertThat(storedOrder.getItems()).singleElement().satisfies(item -> {
                assertThat(item.getProductName()).isEqualTo("운동화");
                assertThat(item.getUnitPrice()).isEqualTo(2_000L);
                assertThat(item.getQuantity()).isEqualTo(2);
            });
            assertThat(storedProduct.getStockQuantity()).isEqualTo(3);
            assertThat(storedBalance.getBalance()).isEqualTo(10_000L);
        });
    }
}
