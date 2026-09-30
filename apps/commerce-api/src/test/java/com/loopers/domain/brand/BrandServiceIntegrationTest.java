package com.loopers.domain.brand;

import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.utils.DatabaseCleanUp;
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
class BrandServiceIntegrationTest {
    @Autowired
    private BrandService brandService;

    @Autowired
    private BrandJpaRepository brandJpaRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("브랜드를 조회할 때,")
    @Nested
    class Get {
        @DisplayName("존재하는 브랜드 ID를 주면, 해당 브랜드 정보를 반환한다.")
        @Test
        void returnsBrand_whenValidIdIsProvided() {
            // arrange
            BrandModel brand = brandJpaRepository.save(
                new BrandModel("나이키", "스포츠 브랜드", "신발/의류")
            );

            // act
            BrandModel result = brandService.getBrand(brand.getId());

            // assert
            assertAll(
                () -> assertThat(result).isNotNull(),
                () -> assertThat(result.getId()).isEqualTo(brand.getId()),
                () -> assertThat(result.getName()).isEqualTo(brand.getName())
            );
        }

        @DisplayName("존재하지 않는 브랜드 ID를 주면, NOT_FOUND 예외가 발생한다.")
        @Test
        void throwsNotFound_whenBrandDoesNotExist() {
            // arrange
            Long invalidId = 999L;

            // act
            CoreException exception = assertThrows(CoreException.class, () ->
                brandService.getBrand(invalidId)
            );

            // assert
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        }

        @DisplayName("삭제된 브랜드 ID를 주면, NOT_FOUND 예외가 발생한다.")
        @Test
        void throwsNotFound_whenBrandIsDeleted() {
            // arrange
            BrandModel brand = brandJpaRepository.save(
                new BrandModel("나이키", "스포츠 브랜드", "신발/의류")
            );
            brand.delete();
            brandJpaRepository.saveAndFlush(brand);

            // act
            CoreException exception = assertThrows(CoreException.class, () ->
                brandService.getBrand(brand.getId())
            );

            // assert
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        }
    }

    @DisplayName("브랜드를 생성할 때,")
    @Nested
    class Create {
        @DisplayName("이름, 설명, 카테고리가 주어지면, 저장하고 반환한다.")
        @Test
        void savesAndReturnsBrand_whenValidFieldsAreProvided() {
            // act
            BrandModel result = brandService.createBrand("나이키", "스포츠 브랜드", "신발/의류");

            // assert
            assertAll(
                () -> assertThat(result.getId()).isNotNull(),
                () -> assertThat(brandJpaRepository.findById(result.getId())).isPresent()
            );
        }
    }

    @DisplayName("브랜드를 수정할 때,")
    @Nested
    class Update {
        @DisplayName("존재하는 브랜드 ID를 주면, 수정하고 저장한다.")
        @Test
        void updatesAndSavesBrand_whenBrandExists() {
            // arrange
            BrandModel brand = brandJpaRepository.save(
                new BrandModel("나이키", "스포츠 브랜드", "신발/의류")
            );

            // act
            brandService.updateBrand(brand.getId(), "아디다스", "독일 스포츠 브랜드", "신발");

            // assert
            BrandModel result = brandJpaRepository.findById(brand.getId()).orElseThrow();
            assertAll(
                () -> assertThat(result.getName()).isEqualTo("아디다스"),
                () -> assertThat(result.getDescription()).isEqualTo("독일 스포츠 브랜드"),
                () -> assertThat(result.getCategory()).isEqualTo("신발")
            );
        }

        @DisplayName("존재하지 않는 브랜드 ID를 주면, NOT_FOUND 예외가 발생한다.")
        @Test
        void throwsNotFound_whenBrandDoesNotExist() {
            // arrange
            Long invalidId = 999L;

            // act
            CoreException exception = assertThrows(CoreException.class, () ->
                brandService.updateBrand(invalidId, "아디다스", "독일 스포츠 브랜드", "신발")
            );

            // assert
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        }

        @DisplayName("삭제된 브랜드 ID를 주면, NOT_FOUND 예외가 발생한다.")
        @Test
        void throwsNotFound_whenBrandIsDeleted() {
            // arrange
            BrandModel brand = brandJpaRepository.save(
                new BrandModel("나이키", "스포츠 브랜드", "신발/의류")
            );
            brand.delete();
            brandJpaRepository.saveAndFlush(brand);

            // act
            CoreException exception = assertThrows(CoreException.class, () ->
                brandService.updateBrand(brand.getId(), "아디다스", "독일 스포츠 브랜드", "신발")
            );

            // assert
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        }
    }

    @DisplayName("브랜드를 삭제할 때,")
    @Nested
    class Delete {
        @DisplayName("존재하는 브랜드 ID를 주면, soft delete로 표시하고 저장한다.")
        @Test
        void marksBrandAsDeleted_whenBrandExists() {
            // arrange
            BrandModel brand = brandJpaRepository.save(
                new BrandModel("나이키", "스포츠 브랜드", "신발/의류")
            );

            // act
            brandService.deleteBrand(brand.getId());

            // assert
            BrandModel result = brandJpaRepository.findById(brand.getId()).orElseThrow();
            assertThat(result.getDeletedAt()).isNotNull();
        }

        @DisplayName("존재하지 않는 브랜드 ID를 주면, NOT_FOUND 예외가 발생한다.")
        @Test
        void throwsNotFound_whenBrandDoesNotExist() {
            // act
            CoreException exception = assertThrows(CoreException.class, () -> brandService.deleteBrand(999L));

            // assert
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        }

        @DisplayName("이미 삭제된 브랜드 ID를 다시 삭제 요청하면, NOT_FOUND 예외가 발생한다.")
        @Test
        void throwsNotFound_whenBrandIsAlreadyDeleted() {
            // arrange
            BrandModel brand = brandJpaRepository.save(
                new BrandModel("나이키", "스포츠 브랜드", "신발/의류")
            );
            brand.delete();
            brandJpaRepository.saveAndFlush(brand);

            // act
            CoreException exception = assertThrows(CoreException.class, () -> brandService.deleteBrand(brand.getId()));

            // assert
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        }
    }
}
