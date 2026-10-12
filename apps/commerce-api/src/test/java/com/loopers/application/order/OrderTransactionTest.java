package com.loopers.application.order;

import com.loopers.application.brand.BrandFacade;
import com.loopers.application.point.PointFacade;
import com.loopers.application.product.ProductFacade;
import com.loopers.domain.common.Quantity;
import com.loopers.domain.order.OrderQuantity;
import com.loopers.domain.point.ChargeAmount;
import com.loopers.domain.product.Price;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.infrastructure.product.ProductRepositoryImpl;
import com.loopers.support.error.DomainError;
import com.loopers.support.error.DomainException;
import com.loopers.utils.DatabaseCleanUp;
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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(OrderTransactionTest.FailingProductSaveConfig.class)
class OrderTransactionTest {

    private static final Long BUYER = 1L;
    private static final Instant NOW = Instant.parse("2026-10-04T00:00:00Z");
    private static final int INITIAL_STOCK = 10;

    private final OrderFacade orderFacade;
    private final ProductFacade productFacade;
    private final BrandFacade brandFacade;
    private final PointFacade pointFacade;
    private final SaveFailingProductRepository productRepository;
    private final JdbcTemplate jdbcTemplate;
    private final DatabaseCleanUp databaseCleanUp;

    private Long coat;
    private Long knit;

    @Autowired
    OrderTransactionTest(
        OrderFacade orderFacade,
        ProductFacade productFacade,
        BrandFacade brandFacade,
        PointFacade pointFacade,
        SaveFailingProductRepository productRepository,
        JdbcTemplate jdbcTemplate,
        DatabaseCleanUp databaseCleanUp
    ) {
        this.orderFacade = orderFacade;
        this.productFacade = productFacade;
        this.brandFacade = brandFacade;
        this.pointFacade = pointFacade;
        this.productRepository = productRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.databaseCleanUp = databaseCleanUp;
    }

    @BeforeEach
    void setUp() {
        Long brandId = brandFacade.register("무신사", "패션 플랫폼").getId();
        coat = productFacade.register(brandId, "코트", Price.of(10_000)).getId();
        knit = productFacade.register(brandId, "니트", Price.of(5_000)).getId();
        productFacade.adjustStock(coat, Quantity.of(INITIAL_STOCK));
        productFacade.adjustStock(knit, Quantity.of(INITIAL_STOCK));
    }

    @AfterEach
    void tearDown() {
        productRepository.disarm();
        databaseCleanUp.truncateAllTables();
    }

    private Long placeTwoItems(int coatQuantity, int knitQuantity) {
        return orderFacade.place(new OrderCreateCommand(BUYER, List.of(
            new OrderCreateCommand.Line(coat, OrderQuantity.of(coatQuantity)),
            new OrderCreateCommand.Line(knit, OrderQuantity.of(knitQuantity))
        )), NOW).getId();
    }

    @DisplayName("ORDER-015 · 정상: 여러 품목의 재고 · 잔액 · CONFIRMED · 결제액 · 원장이 함께 반영된다")
    @Test
    void confirmsEverythingTogether() {
        pointFacade.charge(BUYER, ChargeAmount.of(50_000), NOW);
        Long orderId = placeTwoItems(2, 1);

        orderFacade.confirm(BUYER, orderId, NOW);

        assertThat(quantityOf(coat)).isEqualTo(INITIAL_STOCK - 2);
        assertThat(quantityOf(knit)).isEqualTo(INITIAL_STOCK - 1);
        assertThat(balance()).isEqualTo(25_000L);
        Map<String, Object> order = order(orderId);
        assertThat(order.get("status")).isEqualTo("CONFIRMED");
        assertThat(((Number) order.get("paid_amount")).longValue()).isEqualTo(25_000L);
        assertThat(ledgerTypes()).containsExactly("CHARGE", "USE");
    }

    @DisplayName("ORDER-015 · 중간 실패: 결제 · 첫 품목 차감 · 주문 상태 SQL 이 나간 뒤 둘째 품목 저장에서 실패하면, 전부 확정 전으로 돌아간다")
    @Test
    void rollsBackEverythingWhenSecondStockSaveFails() {
        pointFacade.charge(BUYER, ChargeAmount.of(50_000), NOW);
        Long orderId = placeTwoItems(2, 1);
        productRepository.failOnSaveOf(knit, coat, orderId, BUYER);

        assertThatThrownBy(() -> orderFacade.confirm(BUYER, orderId, NOW))
            .isInstanceOf(InjectedSaveFailure.class);

        assertThat(productRepository.seenAtFailure())
            .as("실패 직전, 같은 트랜잭션 안에서는 결제 · 첫 품목 차감 · 주문 상태가 이미 DB 에 나가 있었다")
            .containsEntry("balance", 25_000L)
            .containsEntry("coatQuantity", INITIAL_STOCK - 2)
            .containsEntry("orderStatus", "CONFIRMED");
        assertUnconfirmed(orderId);
    }

    @DisplayName("ORDER-013 · 14 · 잔액과 재고가 함께 모자라면, 상품을 잠그기 전에 잔액 부족으로 거절된다")
    @Test
    void rejectsByPointBeforeTouchingStock() {
        pointFacade.charge(BUYER, ChargeAmount.of(20_000), NOW);
        Long orderId = placeTwoItems(2, INITIAL_STOCK + 1);

        assertThatThrownBy(() -> orderFacade.confirm(BUYER, orderId, NOW))
            .isInstanceOf(DomainException.class)
            .hasFieldOrPropertyWithValue("error", DomainError.INSUFFICIENT_POINT);

        assertUnconfirmed(orderId);
    }

    @DisplayName("ORDER-013 · 15 · 두 번째 품목의 재고가 모자라면, 결제와 첫 품목의 차감도 남지 않는다")
    @Test
    void rollsBackFirstItemWhenSecondIsShort() {
        pointFacade.charge(BUYER, ChargeAmount.of(500_000), NOW);
        Long orderId = placeTwoItems(2, INITIAL_STOCK + 1);

        assertThatThrownBy(() -> orderFacade.confirm(BUYER, orderId, NOW))
            .isInstanceOf(DomainException.class)
            .hasFieldOrPropertyWithValue("error", DomainError.INSUFFICIENT_STOCK);

        assertUnconfirmed(orderId);
    }

    @DisplayName("ORDER-014 · 15 · 잔액이 모자라면, 재고를 건드리지 않고 주문은 DRAFT 로 남는다")
    @Test
    void leavesDraftWhenPointIsShort() {
        pointFacade.charge(BUYER, ChargeAmount.of(20_000), NOW);
        Long orderId = placeTwoItems(2, 1);

        assertThatThrownBy(() -> orderFacade.confirm(BUYER, orderId, NOW))
            .isInstanceOf(DomainException.class)
            .hasFieldOrPropertyWithValue("error", DomainError.INSUFFICIENT_POINT);

        assertUnconfirmed(orderId);
    }

    private void assertUnconfirmed(Long orderId) {
        assertThat(quantityOf(coat)).isEqualTo(INITIAL_STOCK);
        assertThat(quantityOf(knit)).isEqualTo(INITIAL_STOCK);
        Map<String, Object> order = order(orderId);
        assertThat(order.get("status")).isEqualTo("DRAFT");
        assertThat(order.get("paid_amount")).isNull();
        assertThat(order.get("confirmed_at")).isNull();
        assertThat(ledgerTypes()).containsExactly("CHARGE");
        assertThat(balance()).isEqualTo(ledgerSum());
    }

    private int quantityOf(Long productId) {
        Integer quantity = jdbcTemplate.queryForObject("SELECT quantity FROM product WHERE id = ?", Integer.class, productId);
        return quantity == null ? -1 : quantity;
    }

    private long balance() {
        Long balance = jdbcTemplate.queryForObject("SELECT balance FROM user_point WHERE user_id = ?", Long.class, BUYER);
        return balance == null ? -1 : balance;
    }

    private long ledgerSum() {
        Long sum = jdbcTemplate.queryForObject(
            "SELECT COALESCE(SUM(CASE WHEN type = 'CHARGE' THEN amount ELSE -amount END), 0) "
                + "FROM point_transaction WHERE user_id = ?", Long.class, BUYER);
        return sum == null ? -1 : sum;
    }

    private List<String> ledgerTypes() {
        return jdbcTemplate.queryForList(
            "SELECT type FROM point_transaction WHERE user_id = ? ORDER BY id", String.class, BUYER);
    }

    private Map<String, Object> order(Long orderId) {
        return jdbcTemplate.queryForMap("SELECT status, paid_amount, confirmed_at FROM orders WHERE id = ?", orderId);
    }

    static class InjectedSaveFailure extends RuntimeException {
        InjectedSaveFailure(Long productId) {
            super("테스트가 주입한 상품 저장 실패: productId=" + productId);
        }
    }

    static class SaveFailingProductRepository implements ProductRepository {

        private final ProductRepository delegate;
        private final JdbcTemplate jdbcTemplate;
        private volatile Long failingProductId;
        private volatile Long observedProductId;
        private volatile Long observedOrderId;
        private volatile Long observedUserId;
        private volatile Map<String, Object> seenAtFailure;

        SaveFailingProductRepository(ProductRepository delegate, JdbcTemplate jdbcTemplate) {
            this.delegate = delegate;
            this.jdbcTemplate = jdbcTemplate;
        }

        void failOnSaveOf(Long productId, Long observedProductId, Long observedOrderId, Long observedUserId) {
            this.observedProductId = observedProductId;
            this.observedOrderId = observedOrderId;
            this.observedUserId = observedUserId;
            this.failingProductId = productId;
        }

        void disarm() {
            this.failingProductId = null;
            this.seenAtFailure = null;
        }

        Map<String, Object> seenAtFailure() {
            return seenAtFailure;
        }

        @Override
        public Product save(Product product) {
            if (failingProductId != null && Objects.equals(product.getId(), failingProductId)) {
                seenAtFailure = Map.of(
                    "balance", jdbcTemplate.queryForObject(
                        "SELECT balance FROM user_point WHERE user_id = ?", Long.class, observedUserId),
                    "coatQuantity", jdbcTemplate.queryForObject(
                        "SELECT quantity FROM product WHERE id = ?", Integer.class, observedProductId),
                    "orderStatus", jdbcTemplate.queryForObject(
                        "SELECT status FROM orders WHERE id = ?", String.class, observedOrderId));
                throw new InjectedSaveFailure(failingProductId);
            }
            return delegate.save(product);
        }

        @Override
        public Optional<Product> findById(Long id) {
            return delegate.findById(id);
        }

        @Override
        public Optional<Product> findByIdForUpdate(Long id) {
            return delegate.findByIdForUpdate(id);
        }

        @Override
        public void deleteAllByBrandId(Long brandId) {
            delegate.deleteAllByBrandId(brandId);
        }
    }

    @TestConfiguration
    static class FailingProductSaveConfig {

        @Bean
        @Primary
        SaveFailingProductRepository saveFailingProductRepository(ProductRepositoryImpl real, JdbcTemplate jdbcTemplate) {
            return new SaveFailingProductRepository(real, jdbcTemplate);
        }
    }
}
