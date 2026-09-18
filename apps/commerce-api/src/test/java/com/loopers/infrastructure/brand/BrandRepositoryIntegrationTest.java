package com.loopers.infrastructure.brand;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.product.Product;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

@SpringBootTest
class BrandRepositoryIntegrationTest {

    @Autowired
    private BrandRepository brandRepository;

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

    private <T> T inTransaction(Supplier<T> action) {
        return transactionTemplate.execute(status -> action.get());
    }

    private Brand saveBrand(String name, boolean deleted) {
        return inTransaction(() -> {
            Brand brand = new Brand(name, null);
            if (deleted) {
                brand.delete();
            }
            entityManager.persist(brand);
            return brand;
        });
    }

    private void saveProduct(Brand brand, int stock, boolean deleted) {
        inTransaction(() -> {
            Product product = new Product(entityManager.find(Brand.class, brand.getId()), "상품", 1_000L);
            product.changeStock(stock);
            if (deleted) {
                product.delete();
            }
            entityManager.persist(product);
            return product;
        });
    }

    @DisplayName("브랜드에 삭제되지 않은 상품이 있는지 물을 때, ")
    @Nested
    class HasActiveProduct {
        @DisplayName("재고가 0 인 삭제되지 않은 상품도 있다고 답한다.")
        @Test
        void returnsTrue_whenActiveProductHasZeroStock() {
            // arrange
            Brand brand = saveBrand("브랜드", false);
            saveProduct(brand, 0, false);

            // act & assert
            assertThat(brandRepository.hasActiveProduct(brand.getId())).isTrue();
        }

        @DisplayName("삭제된 상품만 있으면 없다고 답한다.")
        @Test
        void returnsFalse_whenOnlyDeletedProductsRemain() {
            // arrange
            Brand brand = saveBrand("브랜드", false);
            saveProduct(brand, 10, true);

            // act & assert
            assertThat(brandRepository.hasActiveProduct(brand.getId())).isFalse();
        }

        @DisplayName("다른 브랜드의 상품은 세지 않는다.")
        @Test
        void returnsFalse_whenOnlyOtherBrandHasProducts() {
            // arrange
            Brand brand = saveBrand("브랜드", false);
            Brand other = saveBrand("다른 브랜드", false);
            saveProduct(other, 10, false);

            // act & assert
            assertThat(brandRepository.hasActiveProduct(brand.getId())).isFalse();
        }
    }

    @DisplayName("삭제되지 않은 브랜드를 조회할 때, ")
    @Nested
    class FindActive {
        @DisplayName("삭제된 브랜드는 상세와 목록에서 빠진다.")
        @Test
        void excludesDeletedBrands() {
            // arrange
            Brand active = saveBrand("살아 있는 브랜드", false);
            Brand deleted = saveBrand("삭제된 브랜드", true);

            // act
            Page<Brand> page = inTransaction(() -> brandRepository.findActive(PageRequest.of(0, 20)));

            // assert
            assertAll(
                () -> assertThat(brandRepository.findActive(active.getId())).isPresent(),
                () -> assertThat(brandRepository.findActive(deleted.getId())).isEmpty(),
                () -> assertThat(page.getContent()).extracting(Brand::getId).containsExactly(active.getId()),
                () -> assertThat(page.getTotalElements()).isEqualTo(1)
            );
        }

        @DisplayName("목록은 생성 시각 역순이며, 같으면 식별자 역순이다.")
        @Test
        void sortsByCreatedAtDescThenIdDesc() {
            // arrange
            Brand first = saveBrand("첫 번째", false);
            Brand second = saveBrand("두 번째", false);

            // act
            Page<Brand> page = inTransaction(() -> brandRepository.findActive(PageRequest.of(0, 20)));

            // assert
            assertThat(page.getContent()).extracting(Brand::getId).containsExactly(second.getId(), first.getId());
        }
    }
}
