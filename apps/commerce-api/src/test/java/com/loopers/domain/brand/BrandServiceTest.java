package com.loopers.domain.brand;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
class BrandServiceTest {

    @Autowired
    private BrandService brandService;

    @Autowired
    private BrandRepository brandRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("브랜드를 조회할 때,")
    @Nested
    class Get {
        @DisplayName("존재하는 브랜드이면 해당 브랜드를 반환한다.")
        @Test
        void returnsBrand_whenBrandExists() {
            // arrange
            Brand saved = brandRepository.save(Brand.create("루퍼스"));

            // act
            Brand found = brandService.get(saved.getId());

            // assert
            assertThat(found.getId()).isEqualTo(saved.getId());
            assertThat(found.getName()).isEqualTo("루퍼스");
        }

        @DisplayName("존재하지 않는 브랜드이면 NOT_FOUND 로 거절한다.")
        @Test
        void throwsNotFound_whenBrandDoesNotExist() {
            // act
            CoreException exception = assertThrows(CoreException.class, () -> brandService.get(999L));

            // assert
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        }

        @DisplayName("삭제된 브랜드이면 NOT_FOUND 로 거절한다.")
        @Test
        void throwsNotFound_whenBrandIsDeleted() {
            // arrange
            Brand brand = Brand.create("삭제됨");
            brand.delete();
            Brand saved = brandRepository.save(brand);

            // act
            CoreException exception = assertThrows(CoreException.class, () -> brandService.get(saved.getId()));

            // assert
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        }
    }

    @DisplayName("브랜드를 등록할 때,")
    @Nested
    class Create {
        @DisplayName("저장하고, 저장된 브랜드를 반환한다.")
        @Test
        void savesBrand() {
            // act
            Brand created = brandService.create("  루퍼스  ");

            // assert
            assertThat(brandRepository.findActive(created.getId()))
                .get()
                .extracting(Brand::getName)
                .isEqualTo("루퍼스");
        }
    }

    @DisplayName("id 목록으로 브랜드를 일괄 조회할 때,")
    @Nested
    class GetAllByIds {
        @DisplayName("id 를 키로 한 맵을 반환하고, 삭제됐거나 없는 브랜드는 담기지 않는다.")
        @Test
        void returnsMapOfActiveBrands() {
            // arrange
            Brand first = brandService.create("첫째");
            Brand second = brandService.create("둘째");
            Brand deleted = Brand.create("삭제됨");
            deleted.delete();
            Brand savedDeleted = brandRepository.save(deleted);

            // act
            Map<Long, Brand> brands = brandService.getAllByIds(
                List.of(first.getId(), second.getId(), savedDeleted.getId(), 999L));

            // assert
            assertThat(brands).containsOnlyKeys(first.getId(), second.getId());
            assertThat(brands.get(first.getId()).getName()).isEqualTo("첫째");
        }
    }
}
