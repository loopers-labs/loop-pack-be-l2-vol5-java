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

    @DisplayName("브랜드를 조회할 때, ")
    @Nested
    class Get {
        @DisplayName("존재하며 삭제되지 않은 브랜드 id를 주면, 해당 브랜드를 반환한다.")
        @Test
        void returnsBrand_whenIdIsActive() {
            // arrange
            BrandModel saved = brandJpaRepository.save(new BrandModel("나이키"));

            // act
            BrandModel result = brandService.getBrand(saved.getId());

            // assert
            assertThat(result.getName()).isEqualTo("나이키");
        }

        @DisplayName("존재하지 않는 id를 주면, NOT_FOUND 예외가 발생한다.")
        @Test
        void throwsNotFoundException_whenIdDoesNotExist() {
            // act
            CoreException result = assertThrows(CoreException.class, () -> brandService.getBrand(999L));

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        }

        @DisplayName("삭제된 브랜드의 id를 주면, NOT_FOUND 예외가 발생한다.")
        @Test
        void throwsNotFoundException_whenBrandIsDeleted() {
            // arrange
            BrandModel saved = brandJpaRepository.save(new BrandModel("나이키"));
            saved.delete();
            brandJpaRepository.save(saved);

            // act
            CoreException result = assertThrows(CoreException.class, () -> brandService.getBrand(saved.getId()));

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        }
    }
}
