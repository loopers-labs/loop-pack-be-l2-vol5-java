package com.loopers.application.brand;

import com.loopers.application.brand.dto.BrandInfo;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
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
class BrandFacadeTest {

    @Autowired
    private BrandFacade brandFacade;

    @Autowired
    private BrandRepository brandRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("브랜드 상세를 조회할 때,")
    @Nested
    class Get {
        @DisplayName("요청자를 식별하지 않아도 저장된 브랜드 정보를 반환한다.")
        @Test
        void returnsInfo() {
            // arrange
            Brand brand = brandRepository.save(Brand.create("루퍼스"));

            // act
            BrandInfo info = brandFacade.get(brand.getId());

            // assert
            assertThat(info.id()).isEqualTo(brand.getId());
            assertThat(info.name()).isEqualTo("루퍼스");
        }

        @DisplayName("없거나 삭제된 브랜드이면 NOT_FOUND 로 거절한다.")
        @Test
        void throwsNotFound_whenBrandDoesNotExistOrIsDeleted() {
            // arrange
            Brand deleted = Brand.create("삭제됨");
            deleted.delete();
            Brand saved = brandRepository.save(deleted);

            // act
            CoreException missing = assertThrows(CoreException.class, () -> brandFacade.get(999L));
            CoreException removed = assertThrows(CoreException.class, () -> brandFacade.get(saved.getId()));

            // assert
            assertThat(missing.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
            assertThat(removed.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        }
    }
}
