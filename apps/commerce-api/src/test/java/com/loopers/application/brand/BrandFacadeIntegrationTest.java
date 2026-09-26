package com.loopers.application.brand;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
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
class BrandFacadeIntegrationTest {

    @Autowired
    private BrandFacade brandFacade;

    @Autowired
    private BrandRepository brandRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @PersistenceContext
    private EntityManager entityManager;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("Brand를 등록할 때,")
    @Nested
    class Register {
        @DisplayName("등록되지 않은 이름이면, Brand를 저장하고 반환한다.")
        @Test
        void registersBrand_whenNameIsNotRegistered() {
            // act
            BrandInfo result = brandFacade.register("Nike");

            // assert
            entityManager.clear();
            Brand savedBrand = brandRepository.findById(result.id()).orElseThrow();
            assertAll(
                () -> assertThat(result.name()).isEqualTo("Nike"),
                () -> assertThat(savedBrand.getName()).isEqualTo("Nike")
            );
        }

        @DisplayName("삭제된 Brand와 같은 이름이면, CONFLICT 예외가 발생한다.")
        @Test
        void throwsException_whenNameMatchesDeletedBrand() {
            // arrange
            Brand deletedBrand = brandRepository.save(Brand.create("Nike"));
            deletedBrand.delete();
            brandRepository.save(deletedBrand);
            entityManager.clear();

            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                brandFacade.register("Nike");
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.CONFLICT);
        }
    }

    @DisplayName("Brand 상세를 조회할 때,")
    @Nested
    class GetDetail {
        @DisplayName("삭제되지 않은 Brand가 있으면, Brand 정보를 반환한다.")
        @Test
        void returnsBrandInfo_whenBrandExists() {
            // arrange
            Brand brand = brandRepository.save(Brand.create("Nike"));

            // act
            BrandInfo result = brandFacade.getDetail(brand.getId());

            // assert
            assertAll(
                () -> assertThat(result.id()).isEqualTo(brand.getId()),
                () -> assertThat(result.name()).isEqualTo("Nike"),
                () -> assertThat(result.deleted()).isFalse()
            );
        }

        @DisplayName("삭제된 Brand가 있으면, 삭제 상태를 포함한 Brand 정보를 반환한다.")
        @Test
        void returnsDeletedBrandInfo_whenBrandIsDeleted() {
            // arrange
            Brand brand = brandRepository.save(Brand.create("Nike"));
            brand.delete();
            brandRepository.save(brand);
            entityManager.clear();

            // act
            BrandInfo result = brandFacade.getDetail(brand.getId());

            // assert
            assertAll(
                () -> assertThat(result.id()).isEqualTo(brand.getId()),
                () -> assertThat(result.name()).isEqualTo("Nike"),
                () -> assertThat(result.deleted()).isTrue()
            );
        }

        @DisplayName("없는 Brand ID면, NOT_FOUND 예외가 발생한다.")
        @Test
        void throwsException_whenBrandDoesNotExist() {
            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                brandFacade.getDetail(1L);
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        }
    }

    @DisplayName("Brand를 수정할 때,")
    @Nested
    class Update {
        @DisplayName("삭제되지 않은 Brand면, 이름을 변경하고 수정 결과를 반환한다.")
        @Test
        void updatesBrand_whenBrandIsNotDeleted() {
            // arrange
            Brand brand = brandRepository.save(Brand.create("Nike"));

            // act
            BrandInfo result = brandFacade.update(brand.getId(), "Adidas");

            // assert
            entityManager.clear();
            Brand savedBrand = brandRepository.findById(brand.getId()).orElseThrow();
            assertAll(
                () -> assertThat(result.name()).isEqualTo("Adidas"),
                () -> assertThat(savedBrand.getName()).isEqualTo("Adidas")
            );
        }

        @DisplayName("삭제된 Brand면, NOT_FOUND 예외가 발생한다.")
        @Test
        void throwsException_whenBrandIsDeleted() {
            // arrange
            Brand brand = brandRepository.save(Brand.create("Nike"));
            brand.delete();
            brandRepository.save(brand);
            entityManager.clear();

            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                brandFacade.update(brand.getId(), "Adidas");
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        }

        @DisplayName("삭제된 Brand와 같은 이름이면, CONFLICT 예외가 발생한다.")
        @Test
        void throwsException_whenNameMatchesDeletedBrand() {
            // arrange
            Brand brand = brandRepository.save(Brand.create("Nike"));
            Brand deletedBrand = brandRepository.save(Brand.create("Adidas"));
            deletedBrand.delete();
            brandRepository.save(deletedBrand);
            entityManager.clear();

            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                brandFacade.update(brand.getId(), "Adidas");
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.CONFLICT);
        }
    }

    @DisplayName("Brand를 삭제할 때,")
    @Nested
    class Delete {
        @DisplayName("연결된 활성 Product가 없으면, 논리 삭제한다.")
        @Test
        void deletesBrand_whenNoActiveProductExists() {
            // arrange
            Brand brand = brandRepository.save(Brand.create("Nike"));

            // act
            brandFacade.delete(brand.getId());

            // assert
            entityManager.clear();
            Brand deletedBrand = brandRepository.findById(brand.getId()).orElseThrow();
            assertThat(deletedBrand.getDeletedAt()).isNotNull();
        }

        @DisplayName("연결된 Product가 모두 삭제되었으면, Brand를 논리 삭제한다.")
        @Test
        void deletesBrand_whenOnlyDeletedProductsExist() {
            Brand brand = brandRepository.save(Brand.create("Nike"));
            Product product = productRepository.save(Product.create(brand.getId(), "Air Max", 100_000L));
            product.delete();
            productRepository.save(product);

            brandFacade.delete(brand.getId());

            entityManager.clear();
            assertThat(brandRepository.findById(brand.getId()).orElseThrow().getDeletedAt()).isNotNull();
        }

        @DisplayName("재고가 0인 Product라도 연결되어 있으면, CONFLICT 예외가 발생한다.")
        @Test
        void throwsException_whenActiveProductExists() {
            // arrange
            Brand brand = brandRepository.save(Brand.create("Nike"));
            productRepository.save(Product.create(brand.getId(), "Air Max", 100_000L));

            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                brandFacade.delete(brand.getId());
            });

            // assert
            assertAll(
                () -> assertThat(result.getErrorType()).isEqualTo(ErrorType.CONFLICT),
                () -> assertThat(brand.getDeletedAt()).isNull()
            );
        }

        @DisplayName("없는 Brand ID면, NOT_FOUND 예외가 발생한다.")
        @Test
        void throwsException_whenBrandDoesNotExist() {
            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                brandFacade.delete(1L);
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        }
    }

    @DisplayName("Brand 목록을 조회할 때,")
    @Nested
    class GetList {
        @DisplayName("ALL이면 활성·삭제 Brand를 모두 반환한다.")
        @Test
        void returnsAllBrands_whenStatusIsAll() {
            // arrange
            Brand activeBrand = brandRepository.save(Brand.create("Nike"));
            Brand deletedBrand = brandRepository.save(Brand.create("Adidas"));
            deletedBrand.delete();
            brandRepository.save(deletedBrand);

            // act
            List<BrandInfo> result = brandFacade.getList(BrandListStatus.ALL);

            // assert
            assertThat(result).extracting(BrandInfo::id)
                .containsExactlyInAnyOrder(activeBrand.getId(), deletedBrand.getId());
        }

        @DisplayName("ACTIVE이면 삭제되지 않은 Brand만 반환한다.")
        @Test
        void returnsActiveBrands_whenStatusIsActive() {
            // arrange
            Brand activeBrand = brandRepository.save(Brand.create("Nike"));
            Brand deletedBrand = brandRepository.save(Brand.create("Adidas"));
            deletedBrand.delete();
            brandRepository.save(deletedBrand);

            // act
            List<BrandInfo> result = brandFacade.getList(BrandListStatus.ACTIVE);

            // assert
            assertThat(result).extracting(BrandInfo::id).containsExactly(activeBrand.getId());
        }

        @DisplayName("DELETED이면 삭제된 Brand만 반환한다.")
        @Test
        void returnsDeletedBrands_whenStatusIsDeleted() {
            // arrange
            Brand activeBrand = brandRepository.save(Brand.create("Nike"));
            Brand deletedBrand = brandRepository.save(Brand.create("Adidas"));
            deletedBrand.delete();
            brandRepository.save(deletedBrand);

            // act
            List<BrandInfo> result = brandFacade.getList(BrandListStatus.DELETED);

            // assert
            assertThat(result).extracting(BrandInfo::id).containsExactly(deletedBrand.getId());
        }
    }

    @DisplayName("고객이 Brand 상세를 조회할 때,")
    @Nested
    class GetCustomerDetail {
        @DisplayName("삭제되지 않은 Brand가 있으면, Brand 정보를 반환한다.")
        @Test
        void returnsBrandInfo_whenBrandIsActive() {
            // arrange
            Brand brand = brandRepository.save(Brand.create("Nike"));

            // act
            BrandInfo result = brandFacade.getCustomerDetail(brand.getId());

            // assert
            assertAll(
                () -> assertThat(result.id()).isEqualTo(brand.getId()),
                () -> assertThat(result.name()).isEqualTo("Nike"),
                () -> assertThat(result.deleted()).isFalse()
            );
        }

        @DisplayName("삭제된 Brand면, NOT_FOUND 예외가 발생한다.")
        @Test
        void throwsException_whenBrandIsDeleted() {
            // arrange
            Brand brand = brandRepository.save(Brand.create("Nike"));
            brand.delete();
            brandRepository.save(brand);
            entityManager.clear();

            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                brandFacade.getCustomerDetail(brand.getId());
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        }
    }
}
