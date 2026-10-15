package com.loopers.application.order;

import com.loopers.application.brand.BrandApplicationService;
import com.loopers.application.order.port.OrderRepository;
import com.loopers.application.point.PointApplicationService;
import com.loopers.application.product.ProductApplicationService;
import com.loopers.domain.order.Order;
import com.loopers.infrastructure.order.OrderPersistenceAdapter;
import com.loopers.infrastructure.user.UserJpaEntity;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(OrderTransactionTest.FailureInjectionConfig.class)
class OrderTransactionTest {
    @Autowired
    private BrandApplicationService brandApplicationService;

    @Autowired
    private ProductApplicationService productApplicationService;

    @Autowired
    private PointApplicationService pointApplicationService;

    @Autowired
    private OrderApplicationService orderApplicationService;

    @Autowired
    private FailingOrderRepository failingOrderRepository;

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private long firstProductId;

    private long secondProductId;

    @BeforeEach
    void prepare() {
        userJpaRepository.save(new UserJpaEntity(1));
        long brandId = brandApplicationService.create("브랜드").id().value();
        firstProductId = productApplicationService.create(brandId, "첫 상품", 2000, 5).id();
        secondProductId = productApplicationService.create(brandId, "둘째 상품", 3000, 5).id();
        pointApplicationService.charge(1, 10000);
    }

    @AfterEach
    void clean() {
        failingOrderRepository.disarm();
        jdbcTemplate.update("delete from order_items");
        databaseCleanUp.truncateAllTables();
    }

    @Test
    @DisplayName("재고·포인트 차감이 DB에 반영된 뒤 주문 저장에서 실패하면 주문은 DRAFT로 남고 재고와 잔액이 확정 전과 같다")
    void rollsBackStockAndPointWhenOrderSaveFails() {
        OrderResult draft = orderApplicationService.create(1, List.of(
            new OrderApplicationService.ItemRequest(firstProductId, 2),
            new OrderApplicationService.ItemRequest(secondProductId, 1)));
        failingOrderRepository.failOnNextSave();

        assertThatThrownBy(() -> orderApplicationService.confirm(1, draft.id()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage(FailingOrderRepository.INJECTED_FAILURE);

        assertThat(failingOrderRepository.sawAppliedChangesBeforeFailure()).isTrue();
        OrderResult afterFailure = orderApplicationService.getMyOrder(1, draft.id());
        assertThat(afterFailure.status()).isEqualTo("DRAFT");
        assertThat(afterFailure.paidAmount()).isZero();
        assertThat(afterFailure.paymentResult()).isEqualTo("NOT_PAID");
        assertThat(productApplicationService.getAdminProduct(firstProductId).stock()).isEqualTo(5);
        assertThat(productApplicationService.getAdminProduct(secondProductId).stock()).isEqualTo(5);
        assertThat(pointApplicationService.balance(1)).isEqualTo(10000);

        OrderResult confirmed = orderApplicationService.confirm(1, draft.id());
        assertThat(confirmed.status()).isEqualTo("CONFIRMED");
        assertThat(productApplicationService.getAdminProduct(firstProductId).stock()).isEqualTo(3);
        assertThat(productApplicationService.getAdminProduct(secondProductId).stock()).isEqualTo(4);
        assertThat(pointApplicationService.balance(1)).isEqualTo(3000);
    }

    @TestConfiguration
    static class FailureInjectionConfig {
        @Bean
        @Primary
        FailingOrderRepository failingOrderRepository(OrderPersistenceAdapter orderPersistenceAdapter,
            EntityManager entityManager, JdbcTemplate jdbcTemplate) {
            return new FailingOrderRepository(orderPersistenceAdapter, entityManager, jdbcTemplate);
        }
    }

    // 실제 어댑터에 위임하다가 무장된 다음 주문 저장에서 앞선 재고·포인트 변경을 DB로 보낸 뒤 실패를 일으키는 테스트 전용 저장소.
    static class FailingOrderRepository implements OrderRepository {
        static final String INJECTED_FAILURE = "주입된 주문 저장 실패";

        private final OrderRepository delegate;
        private final EntityManager entityManager;
        private final JdbcTemplate jdbcTemplate;
        private final AtomicInteger remainingFailures = new AtomicInteger();
        private final AtomicBoolean sawAppliedChanges = new AtomicBoolean();

        FailingOrderRepository(OrderRepository delegate, EntityManager entityManager, JdbcTemplate jdbcTemplate) {
            this.delegate = delegate;
            this.entityManager = entityManager;
            this.jdbcTemplate = jdbcTemplate;
        }

        void failOnNextSave() {
            sawAppliedChanges.set(false);
            remainingFailures.set(1);
        }

        void disarm() {
            remainingFailures.set(0);
        }

        boolean sawAppliedChangesBeforeFailure() {
            return sawAppliedChanges.get();
        }

        @Override
        public Order save(Order order) {
            if (remainingFailures.getAndUpdate(count -> Math.max(0, count - 1)) > 0) {
                entityManager.flush();
                Integer totalStock = jdbcTemplate.queryForObject("select sum(stock) from products", Integer.class);
                Long balance = jdbcTemplate.queryForObject("select balance from points where user_id = ?", Long.class,
                    order.getUserId());
                sawAppliedChanges.set(totalStock != null && totalStock == 7 && balance != null && balance == 3000L);
                throw new IllegalStateException(INJECTED_FAILURE);
            }
            return delegate.save(order);
        }

        @Override
        public Optional<Order> findById(long id) {
            return delegate.findById(id);
        }

        @Override
        public Optional<Order> findByIdForUpdate(long id) {
            return delegate.findByIdForUpdate(id);
        }

        @Override
        public List<Order> findPage(Long userId, int page, int size) {
            return delegate.findPage(userId, page, size);
        }
    }
}
