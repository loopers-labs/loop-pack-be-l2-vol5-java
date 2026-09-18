package com.loopers.infrastructure.product;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.product.ProductWithBrand;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

@SpringBootTest
class ProductRepositoryIntegrationTest {

    @Autowired
    private ProductRepository productRepository;

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

    private Brand saveBrand(String name) {
        return transactionTemplate.execute(status -> {
            Brand brand = new Brand(name, null);
            entityManager.persist(brand);
            return brand;
        });
    }

    private Product saveProduct(Brand brand, String name, int stock, boolean deleted) {
        return transactionTemplate.execute(status -> {
            Product product = new Product(entityManager.find(Brand.class, brand.getId()), name, 1_000L);
            product.changeStock(stock);
            if (deleted) {
                product.delete();
            }
            entityManager.persist(product);
            return product;
        });
    }

    @DisplayName("상품을 브랜드와 함께 조회하면, 저장한 값과 브랜드 이름이 다시 읽힌다.")
    @Test
    void readsProductWithBrand() {
        // arrange
        Brand brand = saveBrand("브랜드");
        Product product = saveProduct(brand, "상품", 7, false);

        // act
        ProductWithBrand result = productRepository.findActiveWithBrand(product.getId()).orElseThrow();

        // assert
        assertAll(
            () -> assertThat(result.name()).isEqualTo("상품"),
            () -> assertThat(result.price()).isEqualTo(1_000L),
            () -> assertThat(result.stock()).isEqualTo(7),
            () -> assertThat(result.brandId()).isEqualTo(brand.getId()),
            () -> assertThat(result.brandName()).isEqualTo("브랜드"),
            () -> assertThat(result.createdAt()).isNotNull()
        );
    }

    @DisplayName("삭제된 상품은 상세와 목록에서 빠진다.")
    @Test
    void excludesDeletedProducts() {
        // arrange
        Brand brand = saveBrand("브랜드");
        Product active = saveProduct(brand, "살아 있는 상품", 0, false);
        Product deleted = saveProduct(brand, "삭제된 상품", 0, true);

        // act
        Page<ProductWithBrand> page = productRepository.findActiveWithBrand(null, PageRequest.of(0, 20));

        // assert
        assertAll(
            () -> assertThat(productRepository.findActive(deleted.getId())).isEmpty(),
            () -> assertThat(productRepository.findActiveWithBrand(deleted.getId())).isEmpty(),
            () -> assertThat(page.getContent()).extracting(ProductWithBrand::id).containsExactly(active.getId()),
            () -> assertThat(page.getTotalElements()).isEqualTo(1)
        );
    }

    @DisplayName("브랜드로 거르면 그 브랜드의 상품만, 생성 시각 역순으로 돌려준다.")
    @Test
    void filtersByBrandAndSortsByCreatedAtDesc() {
        // arrange
        Brand brand = saveBrand("브랜드");
        Brand other = saveBrand("다른 브랜드");
        Product first = saveProduct(brand, "첫 번째", 0, false);
        saveProduct(other, "다른 브랜드 상품", 0, false);
        Product second = saveProduct(brand, "두 번째", 0, false);

        // act
        Page<ProductWithBrand> page = productRepository.findActiveWithBrand(brand.getId(), PageRequest.of(0, 20));

        // assert
        assertAll(
            () -> assertThat(page.getContent()).extracting(ProductWithBrand::id).containsExactly(second.getId(), first.getId()),
            () -> assertThat(page.getTotalElements()).isEqualTo(2)
        );
    }
}
