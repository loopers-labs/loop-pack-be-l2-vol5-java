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

import java.util.List;

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

    @DisplayName("Product 상세를 조회할 때,")
    @Nested
    class GetDetail {
        @DisplayName("Product가 있으면, 상품·브랜드·재고 정보를 반환한다.")
        @Test
        void returnsProductInfo_whenProductExists() {
            // arrange
            Brand brand = brandJpaRepository.save(Brand.create("Nike"));
            Product product = productJpaRepository.save(Product.create(brand, "Air Max", 100_000L));
            product.changeStockTo(5L);
            productJpaRepository.save(product);

            // act
            ProductInfo result = productFacade.getDetail(product.getId());

            // assert
            assertAll(
                () -> assertThat(result.id()).isEqualTo(product.getId()),
                () -> assertThat(result.brandId()).isEqualTo(brand.getId()),
                () -> assertThat(result.name()).isEqualTo("Air Max"),
                () -> assertThat(result.price()).isEqualTo(100_000L),
                () -> assertThat(result.stock()).isEqualTo(5L),
                () -> assertThat(result.deleted()).isFalse()
            );
        }

        @DisplayName("삭제된 Product가 있으면, 삭제 상태를 포함한 상품 정보를 반환한다.")
        @Test
        void returnsDeletedProductInfo_whenProductIsDeleted() {
            // arrange
            Brand brand = brandJpaRepository.save(Brand.create("Nike"));
            Product product = productJpaRepository.save(Product.create(brand, "Air Max", 100_000L));
            product.delete();
            productJpaRepository.save(product);
            productJpaRepository.flush();
            entityManager.clear();

            // act
            ProductInfo result = productFacade.getDetail(product.getId());

            // assert
            assertThat(result.deleted()).isTrue();
        }

        @DisplayName("없는 Product ID면, NOT_FOUND 예외가 발생한다.")
        @Test
        void throwsException_whenProductDoesNotExist() {
            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                productFacade.getDetail(1L);
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        }
    }

    @DisplayName("Product를 수정할 때,")
    @Nested
    class Update {
        @DisplayName("삭제되지 않은 Product면, 이름과 가격을 변경하고 Brand는 유지한다.")
        @Test
        void updatesProduct_whenProductIsNotDeleted() {
            // arrange
            Brand brand = brandJpaRepository.save(Brand.create("Nike"));
            Product product = productJpaRepository.save(Product.create(brand, "Air Max", 100_000L));

            // act
            ProductInfo result = productFacade.update(product.getId(), "Air Force", 120_000L);

            // assert
            productJpaRepository.flush();
            entityManager.clear();
            Product savedProduct = productJpaRepository.findById(product.getId()).orElseThrow();
            assertAll(
                () -> assertThat(result.name()).isEqualTo("Air Force"),
                () -> assertThat(result.price()).isEqualTo(120_000L),
                () -> assertThat(savedProduct.getBrand().getId()).isEqualTo(brand.getId()),
                () -> assertThat(savedProduct.getName()).isEqualTo("Air Force"),
                () -> assertThat(savedProduct.getPrice()).isEqualTo(120_000L)
            );
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
                productFacade.update(product.getId(), "Air Force", 120_000L);
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        }
    }

    @DisplayName("Product를 삭제할 때,")
    @Nested
    class Delete {
        @DisplayName("Product가 있으면, 논리 삭제 상태로 저장한다.")
        @Test
        void deletesProduct_whenProductExists() {
            // arrange
            Brand brand = brandJpaRepository.save(Brand.create("Nike"));
            Product product = productJpaRepository.save(Product.create(brand, "Air Max", 100_000L));

            // act
            productFacade.delete(product.getId());

            // assert
            productJpaRepository.flush();
            entityManager.clear();
            Product deletedProduct = productJpaRepository.findById(product.getId()).orElseThrow();
            assertThat(deletedProduct.getDeletedAt()).isNotNull();
        }

        @DisplayName("없는 Product ID면, NOT_FOUND 예외가 발생한다.")
        @Test
        void throwsException_whenProductDoesNotExist() {
            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                productFacade.delete(1L);
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        }

        @DisplayName("이미 삭제된 Product면, NOT_FOUND 예외가 발생한다.")
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
                productFacade.delete(product.getId());
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        }
    }

    @DisplayName("Product 목록을 조회할 때,")
    @Nested
    class GetList {
        @DisplayName("ALL이면 활성·삭제 Product를 모두 반환한다.")
        @Test
        void returnsAllProducts_whenStatusIsAll() {
            // arrange
            Brand brand = brandJpaRepository.save(Brand.create("Nike"));
            Product activeProduct = productJpaRepository.save(Product.create(brand, "Air Max", 100_000L));
            Product deletedProduct = productJpaRepository.save(Product.create(brand, "Air Force", 120_000L));
            deletedProduct.delete();
            productJpaRepository.save(deletedProduct);

            // act
            List<ProductInfo> result = productFacade.getList(ProductListStatus.ALL);

            // assert
            assertThat(result).extracting(ProductInfo::id)
                .containsExactlyInAnyOrder(activeProduct.getId(), deletedProduct.getId());
        }

        @DisplayName("ACTIVE이면 삭제되지 않은 Product만 반환한다.")
        @Test
        void returnsActiveProducts_whenStatusIsActive() {
            // arrange
            Brand brand = brandJpaRepository.save(Brand.create("Nike"));
            Product activeProduct = productJpaRepository.save(Product.create(brand, "Air Max", 100_000L));
            Product deletedProduct = productJpaRepository.save(Product.create(brand, "Air Force", 120_000L));
            deletedProduct.delete();
            productJpaRepository.save(deletedProduct);

            // act
            List<ProductInfo> result = productFacade.getList(ProductListStatus.ACTIVE);

            // assert
            assertThat(result).extracting(ProductInfo::id).containsExactly(activeProduct.getId());
        }

        @DisplayName("DELETED이면 삭제된 Product만 반환한다.")
        @Test
        void returnsDeletedProducts_whenStatusIsDeleted() {
            // arrange
            Brand brand = brandJpaRepository.save(Brand.create("Nike"));
            Product activeProduct = productJpaRepository.save(Product.create(brand, "Air Max", 100_000L));
            Product deletedProduct = productJpaRepository.save(Product.create(brand, "Air Force", 120_000L));
            deletedProduct.delete();
            productJpaRepository.save(deletedProduct);

            // act
            List<ProductInfo> result = productFacade.getList(ProductListStatus.DELETED);

            // assert
            assertThat(result).extracting(ProductInfo::id).containsExactly(deletedProduct.getId());
        }
    }
}
