package com.loopers.infrastructure.product;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class ProductRepositoryIntegrationTest {

    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private BrandRepository brandRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @ParameterizedTest
    @ValueSource(strings = {"한", "😀"})
    void persistsBrandStockPriceAndUnicodeName(String character) {
        Brand brand = brandRepository.save(new Brand("브랜드"));
        Product saved = productRepository.save(new Product(brand, character.repeat(100), Long.MAX_VALUE, Integer.MAX_VALUE));
        Product loaded = productRepository.findById(saved.getId()).orElseThrow();

        assertThat(loaded).isNotSameAs(saved);
        assertThat(loaded.getName()).isEqualTo(character.repeat(100));
        assertThat(loaded.getPrice()).isEqualTo(Long.MAX_VALUE);
        assertThat(loaded.getStockQuantity()).isEqualTo(Integer.MAX_VALUE);
        assertThat(loaded.getBrand().getId()).isEqualTo(brand.getId());
        assertThat(loaded.getCreatedAt()).isNotNull();
        assertThat(loaded.getUpdatedAt()).isEqualTo(loaded.getCreatedAt());
        assertThat(productRepository.findBrandId(saved.getId())).contains(brand.getId());
        assertThat(productRepository.findById(Long.MAX_VALUE)).isEmpty();
    }

    @Test
    void existenceIncludesSoldOutProductsAndExcludesDeletedProductsAndOtherBrands() {
        Brand brand = brandRepository.save(new Brand("대상"));
        Brand other = brandRepository.save(new Brand("다른 브랜드"));
        Product saved = productRepository.save(new Product(brand, "품절 상품", 1, 0));
        assertThat(productRepository.existsNonDeletedByBrandId(brand.getId())).isTrue();
        assertThat(productRepository.existsNonDeletedByBrandId(other.getId())).isFalse();

        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
            productRepository.lockById(saved.getId()).orElseThrow().delete(ZonedDateTime.now()));

        assertThat(productRepository.findById(saved.getId()).orElseThrow().isDeleted()).isTrue();
        assertThat(productRepository.existsNonDeletedByBrandId(brand.getId())).isFalse();
    }

    @Test
    void dirtyCheckingPersistsTheFinalStockAndRollbackPreservesAllColumns() {
        Brand brand = brandRepository.save(new Brand("브랜드"));
        Product saved = productRepository.save(new Product(brand, "상품", 1000, 10));
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> {
            Product product = productRepository.lockById(saved.getId()).orElseThrow();
            product.changeStockQuantityTo(3);
            product.deductStock(1);
            product.update("수정", 2000);
        });
        assertThat(productRepository.findById(saved.getId()).orElseThrow().getStockQuantity()).isEqualTo(2);
        var before = jdbcTemplate.queryForList("SELECT * FROM product ORDER BY id");
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            productRepository.lockById(saved.getId()).orElseThrow().changeStockQuantityTo(0);
            productRepository.save(new Product(brand, "롤백", 1, 0));
            throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbcTemplate.queryForList("SELECT * FROM product ORDER BY id")).isEqualTo(before);
    }
}
