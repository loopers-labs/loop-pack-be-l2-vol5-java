package com.loopers.domain.brand;

import com.loopers.domain.common.PageCondition;
import com.loopers.domain.error.DomainErrorType;
import com.loopers.domain.error.DomainException;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.utils.DatabaseCleanUp;
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

    private Brand deletedBrand(String name) {
        Brand brand = new Brand(name);
        brand.delete();
        return brandJpaRepository.save(brand);
    }

    @DisplayName("브랜드를 등록할 때, ")
    @Nested
    class Register {

        @DisplayName("유효한 이름이면, 저장되고 다시 조회할 수 있다.")
        @Test
        void savesBrand_whenNameIsValid() {
            // act
            Brand brand = brandService.register("루퍼스");

            // assert
            Brand found = brandJpaRepository.findById(brand.getId()).orElseThrow();
            assertAll(
                () -> assertThat(found.getName()).isEqualTo("루퍼스"),
                () -> assertThat(found.getCreatedAt()).isNotNull(),
                () -> assertThat(found.getDeletedAt()).isNull()
            );
        }

        @DisplayName("이름이 비어 있으면, INVALID_VALUE 예외가 발생하고 저장되지 않는다.")
        @Test
        void throwsInvalidValue_whenNameIsBlank() {
            // act
            DomainException result = assertThrows(DomainException.class, () -> brandService.register(" "));

            // assert
            assertAll(
                () -> assertThat(result.getType()).isEqualTo(DomainErrorType.INVALID_VALUE),
                () -> assertThat(brandJpaRepository.count()).isZero()
            );
        }
    }

    @DisplayName("브랜드를 수정할 때, ")
    @Nested
    class Update {

        @DisplayName("삭제되지 않은 브랜드면, 바뀐 이름이 저장된다.")
        @Test
        void updatesName_whenBrandIsActive() {
            // arrange
            Brand brand = brandJpaRepository.save(new Brand("루퍼스"));

            // act
            brandService.update(brand.getId(), "새 루퍼스");

            // assert
            assertThat(brandJpaRepository.findById(brand.getId()).orElseThrow().getName()).isEqualTo("새 루퍼스");
        }

        @DisplayName("삭제된 브랜드면, NOT_FOUND 예외가 발생하고 이름이 유지된다. (DEL-002)")
        @Test
        void throwsNotFound_whenBrandIsDeleted() {
            // arrange
            Brand brand = deletedBrand("루퍼스");

            // act
            DomainException result = assertThrows(DomainException.class, () -> brandService.update(brand.getId(), "새 루퍼스"));

            // assert
            assertAll(
                () -> assertThat(result.getType()).isEqualTo(DomainErrorType.NOT_FOUND),
                () -> assertThat(brandJpaRepository.findById(brand.getId()).orElseThrow().getName()).isEqualTo("루퍼스")
            );
        }
    }

    @DisplayName("브랜드를 삭제할 때, ")
    @Nested
    class Delete {

        @DisplayName("삭제되지 않은 브랜드면, 행은 남고 삭제 시각이 기록되어 고객 조회에서 제외된다. (결정 1)")
        @Test
        void softDeletesBrand_whenBrandIsActive() {
            // arrange
            Brand brand = brandJpaRepository.save(new Brand("루퍼스"));

            // act
            brandService.delete(brand.getId());

            // assert
            assertAll(
                () -> assertThat(brandJpaRepository.findById(brand.getId()).orElseThrow().getDeletedAt()).isNotNull(),
                () -> assertThat(assertThrows(DomainException.class, () -> brandService.getBrand(brand.getId())).getType())
                    .isEqualTo(DomainErrorType.NOT_FOUND)
            );
        }

        @DisplayName("없거나 이미 삭제된 브랜드면, NOT_FOUND 예외가 발생한다.")
        @Test
        void throwsNotFound_whenBrandDoesNotExistOrIsDeleted() {
            // arrange
            Brand deleted = deletedBrand("루퍼스");

            // act
            DomainException notExists = assertThrows(DomainException.class, () -> brandService.delete(999999L));
            DomainException alreadyDeleted = assertThrows(DomainException.class, () -> brandService.delete(deleted.getId()));

            // assert
            assertAll(
                () -> assertThat(notExists.getType()).isEqualTo(DomainErrorType.NOT_FOUND),
                () -> assertThat(alreadyDeleted.getType()).isEqualTo(DomainErrorType.NOT_FOUND)
            );
        }
    }

    @DisplayName("브랜드 목록을 조회할 때, ")
    @Nested
    class GetBrands {

        @DisplayName("삭제된 브랜드는 제외하고 최신순으로 페이지 조회한다.")
        @Test
        void returnsActiveBrandsInLatestOrder() {
            // arrange
            Brand first = brandJpaRepository.save(new Brand("A"));
            Brand second = brandJpaRepository.save(new Brand("B"));
            Brand third = brandJpaRepository.save(new Brand("C"));
            deletedBrand("삭제됨");

            // act
            List<Brand> firstPage = brandService.getBrands(new PageCondition(0, 2));
            List<Brand> secondPage = brandService.getBrands(new PageCondition(1, 2));

            // assert
            assertAll(
                () -> assertThat(firstPage).extracting(Brand::getId).containsExactly(third.getId(), second.getId()),
                () -> assertThat(secondPage).extracting(Brand::getId).containsExactly(first.getId()),
                () -> assertThat(brandService.countBrands()).isEqualTo(3L)
            );
        }
    }
}
