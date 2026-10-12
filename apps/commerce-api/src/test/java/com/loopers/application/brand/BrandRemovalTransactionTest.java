package com.loopers.application.brand;

import com.loopers.application.order.OrderCreateCommand;
import com.loopers.application.order.OrderFacade;
import com.loopers.application.point.PointFacade;
import com.loopers.application.product.ProductFacade;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.common.PageNumber;
import com.loopers.domain.common.PageSize;
import com.loopers.domain.common.PageWindow;
import com.loopers.domain.common.Quantity;
import com.loopers.domain.order.OrderQuantity;
import com.loopers.domain.point.ChargeAmount;
import com.loopers.domain.product.Price;
import com.loopers.infrastructure.brand.BrandRepositoryImpl;
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
@Import(BrandRemovalTransactionTest.FailingSaveConfig.class)
class BrandRemovalTransactionTest {

    private static final Long BUYER = 1L;
    private static final Instant NOW = Instant.parse("2026-10-04T00:00:00Z");

    private final BrandFacade brandFacade;
    private final ProductFacade productFacade;
    private final OrderFacade orderFacade;
    private final PointFacade pointFacade;
    private final SaveFailingBrandRepository brandRepository;
    private final JdbcTemplate jdbcTemplate;
    private final DatabaseCleanUp databaseCleanUp;

    private Long brandId;
    private Long soldOut;
    private Long inStock;
    private Long discontinued;
    private List<Object> discontinuedTimes;
    private Long otherProduct;
    private Long pastOrder;

    @Autowired
    BrandRemovalTransactionTest(
        BrandFacade brandFacade,
        ProductFacade productFacade,
        OrderFacade orderFacade,
        PointFacade pointFacade,
        SaveFailingBrandRepository brandRepository,
        JdbcTemplate jdbcTemplate,
        DatabaseCleanUp databaseCleanUp
    ) {
        this.brandFacade = brandFacade;
        this.productFacade = productFacade;
        this.orderFacade = orderFacade;
        this.pointFacade = pointFacade;
        this.brandRepository = brandRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.databaseCleanUp = databaseCleanUp;
    }

    @BeforeEach
    void setUp() {
        brandId = brandFacade.register("무신사", "패션 플랫폼").getId();
        soldOut = productFacade.register(brandId, "코트", Price.of(129_000)).getId();
        inStock = productFacade.register(brandId, "니트", Price.of(10_000)).getId();
        productFacade.adjustStock(inStock, Quantity.of(5));
        discontinued = productFacade.register(brandId, "단종 코트", Price.of(99_000)).getId();
        productFacade.delete(discontinued);
        jdbcTemplate.update(
            "UPDATE product SET deleted_at = '2020-01-01 00:00:00', updated_at = '2020-01-01 00:00:00' WHERE id = ?",
            discontinued);
        discontinuedTimes = timesOf(discontinued);

        Long otherBrand = brandFacade.register("29CM", "셀렉트샵").getId();
        otherProduct = productFacade.register(otherBrand, "셔츠", Price.of(30_000)).getId();

        pointFacade.charge(BUYER, ChargeAmount.of(50_000), NOW);
        pastOrder = orderFacade.place(new OrderCreateCommand(BUYER,
            List.of(new OrderCreateCommand.Line(inStock, OrderQuantity.of(2)))), NOW).getId();
        orderFacade.confirm(BUYER, pastOrder, NOW);
    }

    @AfterEach
    void tearDown() {
        brandRepository.disarm();
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("BRAND-004 · 정상: 브랜드와 연결 상품 전체가 사용 대상에서 빠지고, 다른 브랜드와 과거 주문은 그대로다")
    @Test
    void removesBrandWithItsProducts() {
        brandFacade.delete(brandId);

        assertThat(brandAlive(brandId)).isFalse();
        assertThat(productAlive(soldOut)).isFalse();
        assertThat(productAlive(inStock)).isFalse();
        assertOthersUntouched();
    }

    @DisplayName("BRAND-004 · 중간 실패: 상품 UPDATE 가 나간 뒤 브랜드 저장에서 실패하면, 브랜드와 상품이 모두 이전 상태로 돌아간다")
    @Test
    void rollsBackEverythingWhenBrandSaveFails() {
        brandRepository.failOnSaveOf(brandId);

        assertThatThrownBy(() -> brandFacade.delete(brandId)).isInstanceOf(InjectedSaveFailure.class);

        assertThat(brandRepository.aliveProductsSeenAtFailure())
            .as("실패 직전, 같은 트랜잭션 안에서는 상품 UPDATE 가 이미 반영되어 있었다")
            .isZero();
        assertThat(brandAlive(brandId)).isTrue();
        assertThat(productAlive(soldOut)).isTrue();
        assertThat(productAlive(inStock)).isTrue();
        assertThat(quantityOf(soldOut)).isZero();
        assertThat(quantityOf(inStock)).isEqualTo(3);
        assertOthersUntouched();
    }

    private void assertOthersUntouched() {
        assertThat(timesOf(discontinued)).as("이미 삭제된 상품의 삭제·수정 시각").isEqualTo(discontinuedTimes);
        assertThat(productAlive(otherProduct)).as("다른 브랜드의 상품").isTrue();
        Map<String, Object> order = jdbcTemplate.queryForMap(
            "SELECT status, total_amount, paid_amount FROM orders WHERE id = ?", pastOrder);
        assertThat(order.get("status")).isEqualTo("CONFIRMED");
        assertThat(((Number) order.get("total_amount")).longValue()).isEqualTo(20_000L);
        assertThat(((Number) order.get("paid_amount")).longValue()).isEqualTo(20_000L);
        Map<String, Object> item = jdbcTemplate.queryForMap(
            "SELECT product_name, unit_price, quantity FROM order_item WHERE order_id = ?", pastOrder);
        assertThat(item.get("product_name")).isEqualTo("니트");
        assertThat(((Number) item.get("unit_price")).longValue()).isEqualTo(10_000L);
        assertThat(((Number) item.get("quantity")).intValue()).isEqualTo(2);
    }

    private boolean brandAlive(Long id) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
            "SELECT deleted_at IS NULL FROM brand WHERE id = ?", Boolean.class, id));
    }

    private boolean productAlive(Long id) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
            "SELECT deleted_at IS NULL FROM product WHERE id = ?", Boolean.class, id));
    }

    private List<Object> timesOf(Long productId) {
        Map<String, Object> row = jdbcTemplate.queryForMap(
            "SELECT deleted_at, updated_at FROM product WHERE id = ?", productId);
        return List.of(row.get("deleted_at"), row.get("updated_at"));
    }

    private int quantityOf(Long productId) {
        Integer quantity = jdbcTemplate.queryForObject(
            "SELECT quantity FROM product WHERE id = ?", Integer.class, productId);
        return quantity == null ? -1 : quantity;
    }

    static class InjectedSaveFailure extends RuntimeException {
        InjectedSaveFailure(Long brandId) {
            super("테스트가 주입한 브랜드 저장 실패: id=" + brandId);
        }
    }

    static class SaveFailingBrandRepository implements BrandRepository {

        private final BrandRepository delegate;
        private final JdbcTemplate jdbcTemplate;
        private volatile Long failingBrandId;
        private volatile Integer aliveProductsSeenAtFailure;

        SaveFailingBrandRepository(BrandRepository delegate, JdbcTemplate jdbcTemplate) {
            this.delegate = delegate;
            this.jdbcTemplate = jdbcTemplate;
        }

        void failOnSaveOf(Long brandId) {
            this.failingBrandId = brandId;
        }

        void disarm() {
            this.failingBrandId = null;
            this.aliveProductsSeenAtFailure = null;
        }

        Integer aliveProductsSeenAtFailure() {
            return aliveProductsSeenAtFailure;
        }

        @Override
        public Brand save(Brand brand) {
            if (failingBrandId != null && Objects.equals(brand.getId(), failingBrandId)) {
                aliveProductsSeenAtFailure = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM product WHERE brand_id = ? AND deleted_at IS NULL",
                    Integer.class, failingBrandId);
                throw new InjectedSaveFailure(failingBrandId);
            }
            return delegate.save(brand);
        }

        @Override
        public Optional<Brand> findById(Long id) {
            return delegate.findById(id);
        }

        @Override
        public Optional<Brand> findByIdForShare(Long id) {
            return delegate.findByIdForShare(id);
        }

        @Override
        public Optional<Brand> findByIdForUpdate(Long id) {
            return delegate.findByIdForUpdate(id);
        }

        @Override
        public PageWindow<Brand> findPage(PageNumber page, PageSize size) {
            return delegate.findPage(page, size);
        }
    }

    @TestConfiguration
    static class FailingSaveConfig {

        @Bean
        @Primary
        SaveFailingBrandRepository saveFailingBrandRepository(BrandRepositoryImpl real, JdbcTemplate jdbcTemplate) {
            return new SaveFailingBrandRepository(real, jdbcTemplate);
        }
    }
}
