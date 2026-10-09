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

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;

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

    @DisplayName("식별자 목록으로 살아 있는 상품을 물으면, 삭제된 상품과 없는 상품은 빠진다. (설계 6.4, D-35)")
    @Test
    void findsOnlyActiveIds() {
        // arrange
        Brand brand = saveBrand("브랜드");
        Product active = saveProduct(brand, "판매 중", 0, false);
        Product deleted = saveProduct(brand, "판매 종료", 0, true);

        // act
        Set<Long> result = productRepository.findActiveIds(List.of(active.getId(), deleted.getId(), 999L));

        // assert
        assertThat(result).containsExactly(active.getId());
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

    @DisplayName("브랜드의 상품을 일괄 삭제하면, 그 브랜드의 삭제되지 않은 상품(재고 0 포함)만 받은 시각으로 삭제하고 그 수를 돌려준다. (BRD-02, 3주차 설계 2.3)")
    @Test
    void deletesAllActiveProductsOfBrand() {
        // arrange
        Brand brand = saveBrand("브랜드");
        Brand other = saveBrand("다른 브랜드");
        Product inStock = saveProduct(brand, "재고 있음", 5, false);
        Product soldOut = saveProduct(brand, "재고 0", 0, false);
        Product alreadyDeleted = saveProduct(brand, "이미 삭제", 5, true);
        Product otherBrands = saveProduct(other, "다른 브랜드 상품", 5, false);
        ZonedDateTime alreadyDeletedAt = alreadyDeleted.getDeletedAt();
        ZonedDateTime deletedAt = ZonedDateTime.of(2026, 10, 9, 12, 0, 0, 0, ZoneOffset.UTC);

        // act
        Integer result = transactionTemplate.execute(status -> productRepository.deleteAllOfBrand(brand.getId(), deletedAt));

        // assert
        List<Product> reloaded = transactionTemplate.execute(status -> List.of(
            entityManager.find(Product.class, inStock.getId()),
            entityManager.find(Product.class, soldOut.getId()),
            entityManager.find(Product.class, alreadyDeleted.getId()),
            entityManager.find(Product.class, otherBrands.getId())
        ));
        assertAll(
            () -> assertThat(result).isEqualTo(2),
            () -> assertThat(reloaded.get(0).getDeletedAt().toInstant()).isEqualTo(deletedAt.toInstant()),
            () -> assertThat(reloaded.get(0).getUpdatedAt().toInstant()).isEqualTo(deletedAt.toInstant()),
            () -> assertThat(reloaded.get(1).getDeletedAt().toInstant()).isEqualTo(deletedAt.toInstant()),
            () -> assertThat(reloaded.get(2).getDeletedAt().toInstant()).isEqualTo(alreadyDeletedAt.toInstant()),
            () -> assertThat(reloaded.get(3).isDeleted()).isFalse()
        );
    }

    @DisplayName("삭제되지 않은 상품이 없는 브랜드면, 아무것도 바꾸지 않고 0 을 돌려준다.")
    @Test
    void deletesNothing_whenBrandHasNoActiveProduct() {
        // arrange
        Brand brand = saveBrand("브랜드");

        // act
        Integer result = transactionTemplate.execute(
            status -> productRepository.deleteAllOfBrand(brand.getId(), ZonedDateTime.now(ZoneOffset.UTC))
        );

        // assert
        assertThat(result).isZero();
    }
}
