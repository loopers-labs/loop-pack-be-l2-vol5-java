package com.loopers.infrastructure;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class BrandProductRepositoryIntegrationTest {

    @Autowired
    private BrandRepository brandRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    @DisplayName("목록 조회 인덱스의 브랜드·삭제·가격 순서를 유지하고 실험용 가격 단일 인덱스는 남기지 않는다.")
    void keepsCompositeIndexWithoutExperimentalPriceIndex() {
        assertThat(jdbcTemplate.queryForList("""
            SELECT COLUMN_NAME
            FROM information_schema.statistics
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'products'
              AND INDEX_NAME = 'idx_products_brand_deleted_price'
            ORDER BY SEQ_IN_INDEX
            """, String.class)).containsExactly("brand_id", "deleted_at", "price");
        assertThat(jdbcTemplate.queryForList("""
            SELECT COLUMN_NAME
            FROM information_schema.statistics
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'products'
              AND INDEX_NAME = 'FKa3a4mpsfdf4d2y6r8ra3sc8mv'
            ORDER BY SEQ_IN_INDEX
            """, String.class)).containsExactly("brand_id");
        assertThat(jdbcTemplate.queryForObject("""
            SELECT COUNT(*)
            FROM information_schema.statistics
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'products'
              AND INDEX_NAME = 'idx_products_price'
            """, Integer.class)).isZero();
    }

    @Test
    void mapsBrandAndProductBetweenDomainAndJpaModels() {
        Brand brand = brandRepository.save(Brand.create("Nike"));
        Product product = productRepository.save(Product.create(brand.getId(), "Air Max", 100_000L));

        product.changeStockTo(7L);
        product.updateDetails("Air Max 90", 120_000L);
        productRepository.save(product);
        entityManager.flush();
        entityManager.clear();

        Brand reloadedBrand = brandRepository.findById(brand.getId()).orElseThrow();
        Product reloadedProduct = productRepository.findById(product.getId()).orElseThrow();

        assertThat(reloadedBrand.getName()).isEqualTo("Nike");
        assertThat(reloadedBrand.getCreatedAt()).isNotNull();
        assertThat(reloadedProduct.getBrandId()).isEqualTo(brand.getId());
        assertThat(reloadedProduct.getName()).isEqualTo("Air Max 90");
        assertThat(reloadedProduct.getPrice()).isEqualTo(120_000L);
        assertThat(reloadedProduct.getStock().amount()).isEqualTo(7L);
        assertThat(reloadedProduct.getCreatedAt()).isNotNull();
        assertThat(brandRepository.findActiveById(brand.getId())).isPresent();
    }

    @Test
    void preservesDeletionTimeAcrossDomainAndJpaModels() {
        Brand brand = brandRepository.save(Brand.create("Nike"));
        Product product = productRepository.save(Product.create(brand.getId(), "Air Max", 100_000L));
        ZonedDateTime deletedAt = ZonedDateTime.parse("2026-01-02T03:04:05Z");

        productRepository.save(Product.reconstitute(
            product.getId(), product.getBrandId(), product.getName(), product.getPrice(),
            product.getStock().amount(), product.getCreatedAt(), deletedAt
        ));
        brandRepository.save(Brand.reconstitute(
            brand.getId(), brand.getName(), brand.getCreatedAt(), deletedAt
        ));
        entityManager.flush();
        entityManager.clear();

        Brand reloadedBrand = brandRepository.findById(brand.getId()).orElseThrow();
        Product reloadedProduct = productRepository.findById(product.getId()).orElseThrow();
        assertThat(reloadedBrand.getDeletedAt().toInstant()).isEqualTo(deletedAt.toInstant());
        assertThat(reloadedProduct.getDeletedAt().toInstant()).isEqualTo(deletedAt.toInstant());
        assertThat(brandRepository.findActiveById(brand.getId())).isEmpty();
    }
}
