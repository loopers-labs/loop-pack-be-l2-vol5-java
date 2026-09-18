package com.loopers.application.product;

import com.loopers.application.user.UserResolutionException;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.product.ProductException;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.product.ProductStockException;
import com.loopers.domain.user.UserRole;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class AdminProductServiceIntegrationTest {

    @Autowired
    private AdminProductService service;
    @Autowired
    private BrandRepository brandRepository;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @Test
    void createsUpdatesAndReplacesStockWithTheSameBrand() {
        Brand brand = brandRepository.save(new Brand("브랜드"));
        AdminProductInfo created = service.create(UserRole.ADMIN, brand.getId(), " 상품 ", 1000, 10);
        AdminProductInfo updated = service.update(UserRole.ADMIN, created.productId(), " 수정 ", 2000);
        AdminProductInfo stocked = service.changeStock(UserRole.ADMIN, created.productId(), 3);

        assertThat(created.productId()).isPositive();
        assertThat(created.name()).isEqualTo("상품");
        assertThat(created.createdAt()).isNotNull();
        assertThat(created.likeCount()).isZero();
        assertThat(updated.name()).isEqualTo("수정");
        assertThat(updated.price()).isEqualTo(2000);
        assertThat(updated.stockQuantity()).isEqualTo(10);
        assertThat(updated.brand().brandId()).isEqualTo(brand.getId());
        assertThat(stocked.stockQuantity()).isEqualTo(3);
        assertThat(productRepository.findById(created.productId()).orElseThrow().getStockQuantity()).isEqualTo(3);
    }

    @Test
    void authorizationPrecedesInvalidTargetAndInputsWithoutChangingData() {
        Brand brand = brandRepository.save(new Brand("브랜드"));
        AdminProductInfo product = service.create(UserRole.ADMIN, brand.getId(), "상품", 1000, 5);
        var before = jdbcTemplate.queryForList("SELECT * FROM product ORDER BY id");

        assertThatThrownBy(() -> service.create(UserRole.CUSTOMER, Long.MAX_VALUE, " ", 0, -1))
            .isInstanceOfSatisfying(UserResolutionException.class,
                error -> assertThat(error.getReason()).isEqualTo(UserResolutionException.Reason.ADMIN_REQUIRED));
        assertThatThrownBy(() -> service.update(UserRole.CUSTOMER, product.productId(), " ", 0))
            .isInstanceOf(UserResolutionException.class);
        assertThatThrownBy(() -> service.changeStock(UserRole.CUSTOMER, product.productId(), -1))
            .isInstanceOf(UserResolutionException.class);
        assertThatThrownBy(() -> service.delete(UserRole.CUSTOMER, product.productId()))
            .isInstanceOf(UserResolutionException.class);
        assertThat(jdbcTemplate.queryForList("SELECT * FROM product ORDER BY id")).isEqualTo(before);
    }

    @Test
    void missingOrDeletedBrandCannotReceiveNewProducts() {
        Brand brand = brandRepository.save(new Brand("삭제 브랜드"));
        jdbcTemplate.update("UPDATE brand SET deleted_at = CURRENT_TIMESTAMP(6) WHERE id = ?", brand.getId());

        assertThatThrownBy(() -> service.create(UserRole.ADMIN, Long.MAX_VALUE, "상품", 1000, 1))
            .isInstanceOfSatisfying(ProductQueryException.class,
                error -> assertThat(error.getReason()).isEqualTo(ProductQueryException.Reason.BRAND_NOT_FOUND));
        assertThatThrownBy(() -> service.create(UserRole.ADMIN, brand.getId(), "상품", 1000, 1))
            .isInstanceOf(ProductQueryException.class);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM product", Long.class)).isZero();
    }

    @Test
    void invalidChangesPreserveEveryStoredColumn() {
        Brand brand = brandRepository.save(new Brand("브랜드"));
        AdminProductInfo product = service.create(UserRole.ADMIN, brand.getId(), "상품", 1000, 5);
        var before = jdbcTemplate.queryForList("SELECT * FROM product ORDER BY id");

        assertThatThrownBy(() -> service.update(UserRole.ADMIN, product.productId(), "수정", 0))
            .isInstanceOf(ProductException.class);
        assertThatThrownBy(() -> service.update(UserRole.ADMIN, product.productId(), "😀".repeat(101), 2000))
            .isInstanceOf(ProductException.class);
        assertThatThrownBy(() -> service.changeStock(UserRole.ADMIN, product.productId(), -1))
            .isInstanceOf(ProductStockException.class);
        assertThatThrownBy(() -> service.create(UserRole.ADMIN, brand.getId(), "상품", 0, 1))
            .isInstanceOf(ProductException.class);
        assertThat(jdbcTemplate.queryForList("SELECT * FROM product ORDER BY id")).isEqualTo(before);
    }

    @Test
    void deletionIsIdempotentAndDeletedProductsRejectChanges() {
        Brand brand = brandRepository.save(new Brand("브랜드"));
        AdminProductInfo product = service.create(UserRole.ADMIN, brand.getId(), "상품", 1000, 5);
        AdminProductInfo deleted = service.delete(UserRole.ADMIN, product.productId());
        var before = jdbcTemplate.queryForList("SELECT * FROM product ORDER BY id");
        AdminProductInfo repeated = service.delete(UserRole.ADMIN, product.productId());

        assertThat(deleted.deletedAt()).isNotNull();
        assertThat(repeated.deletedAt().toInstant()).isEqualTo(deleted.deletedAt().toInstant());
        assertThatThrownBy(() -> service.update(UserRole.ADMIN, product.productId(), "수정", 2000))
            .isInstanceOf(ProductQueryException.class);
        assertThatThrownBy(() -> service.changeStock(UserRole.ADMIN, product.productId(), 0))
            .isInstanceOf(ProductQueryException.class);
        assertThatThrownBy(() -> service.delete(UserRole.ADMIN, Long.MAX_VALUE)).isInstanceOf(ProductQueryException.class);
        assertThat(jdbcTemplate.queryForList("SELECT * FROM product ORDER BY id")).isEqualTo(before);
    }
}
