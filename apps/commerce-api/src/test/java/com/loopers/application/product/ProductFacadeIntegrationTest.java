package com.loopers.application.product;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.product.Product;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
class ProductFacadeIntegrationTest {

    @Autowired
    private ProductFacade productFacade;

    @Autowired
    private BrandJpaRepository brandJpaRepository;

    @Autowired
    private ProductJpaRepository productJpaRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @PersistenceContext
    private EntityManager entityManager;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("Product를 등록할 때,")
    @Nested
    class Register {
        @DisplayName("삭제되지 않은 Brand를 참조하면, 재고 0인 Product를 저장하고 반환한다.")
        @Test
        void registersProduct_whenBrandIsNotDeleted() {
            // arrange
            Brand brand = brandJpaRepository.save(Brand.create("Nike"));

            // act
            ProductInfo result = productFacade.register(brand.getId(), "Air Max", 100_000L);

            // assert
            productJpaRepository.flush();
            entityManager.clear();
            Product savedProduct = productJpaRepository.findById(result.id()).orElseThrow();
            assertAll(
                () -> assertThat(result.brandId()).isEqualTo(brand.getId()),
                () -> assertThat(result.name()).isEqualTo("Air Max"),
                () -> assertThat(result.price()).isEqualTo(100_000L),
                () -> assertThat(result.stock()).isZero(),
                () -> assertThat(savedProduct.getBrand().getId()).isEqualTo(brand.getId()),
                () -> assertThat(savedProduct.getStock().amount()).isZero()
            );
        }

        @DisplayName("없는 Brand를 참조하면, NOT_FOUND 예외가 발생한다.")
        @Test
        void throwsException_whenBrandDoesNotExist() {
            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                productFacade.register(1L, "Air Max", 100_000L);
            });

            // assert
            assertAll(
                () -> assertThat(result.getErrorType()).isEqualTo(ErrorType.NOT_FOUND),
                () -> assertThat(productJpaRepository.count()).isZero()
            );
        }

        @DisplayName("삭제된 Brand를 참조하면, NOT_FOUND 예외가 발생한다.")
        @Test
        void throwsException_whenBrandIsDeleted() {
            // arrange
            Brand brand = brandJpaRepository.save(Brand.create("Nike"));
            brand.delete();
            brandJpaRepository.save(brand);
            brandJpaRepository.flush();
            entityManager.clear();

            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                productFacade.register(brand.getId(), "Air Max", 100_000L);
            });

            // assert
            assertAll(
                () -> assertThat(result.getErrorType()).isEqualTo(ErrorType.NOT_FOUND),
                () -> assertThat(productJpaRepository.count()).isZero()
            );
        }
    }

    @DisplayName("Product 재고를 변경할 때,")
    @Nested
    class ChangeStock {
        @DisplayName("삭제되지 않은 Product면, 최종 재고 수량을 저장하고 반환한다.")
        @Test
        void changesStock_whenProductIsNotDeleted() {
            // arrange
            Brand brand = brandJpaRepository.save(Brand.create("Nike"));
            Product product = productJpaRepository.save(Product.create(brand, "Air Max", 100_000L));

            // act
            ProductInfo result = productFacade.changeStock(product.getId(), 5L);

            // assert
            productJpaRepository.flush();
            entityManager.clear();
            Product savedProduct = productJpaRepository.findById(product.getId()).orElseThrow();
            assertAll(
                () -> assertThat(result.stock()).isEqualTo(5L),
                () -> assertThat(savedProduct.getStock().amount()).isEqualTo(5L)
            );
        }

        @DisplayName("없는 Product면, NOT_FOUND 예외가 발생한다.")
        @Test
        void throwsException_whenProductDoesNotExist() {
            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                productFacade.changeStock(1L, 5L);
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        }

        @DisplayName("삭제된 Product면, NOT_FOUND 예외가 발생한다.")
        @Test
        void throwsException_whenProductIsDeleted() {
            // arrange
            Brand brand = brandJpaRepository.save(Brand.create("Nike"));
            Product product = productJpaRepository.save(Product.create(brand, "Air Max", 100_000L));
            product.delete();
            productJpaRepository.save(product);
            productJpaRepository.flush();
            entityManager.clear();

            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                productFacade.changeStock(product.getId(), 5L);
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        }
    }
}
