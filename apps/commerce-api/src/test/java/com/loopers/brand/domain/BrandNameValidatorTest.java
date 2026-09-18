package com.loopers.brand.domain;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BrandNameValidatorTest {

    @DisplayName("[P-ADMIN-01] 삭제되지 않은 브랜드끼리는 이름이 달라야 한다.")
    @Nested
    class ValidateBrandNameDuplication {

        @DisplayName("[의사결정표] 활성 브랜드와 이름이 같으면 중복으로 거절한다.")
        @Test
        void throwsDuplicateBrandName_whenActiveBrandHasSameName() {
            BrandRepository repository = mock(BrandRepository.class);
            when(repository.findAllByName("Nike")).thenReturn(List.of(new Brand("Nike")));
            BrandNameValidator validator = new BrandNameValidator(repository);

            CoreException result = assertThrows(
                CoreException.class,
                () -> validator.validateNotDuplicated("Nike")
            );

            assertThat(result.getErrorCode()).isEqualTo(ErrorCode.DUPLICATE_BRAND_NAME);
        }

        @DisplayName("[의사결정표] 다른 대소문자이거나 삭제된 브랜드와 같은 이름이면 허용한다.")
        @Test
        void allowsName_whenCaseDiffersOrMatchedBrandIsDeleted() {
            BrandRepository repository = mock(BrandRepository.class);
            Brand deleted = new Brand("Nike");
            deleted.delete();
            when(repository.findAllByName("nike")).thenReturn(List.of());
            when(repository.findAllByName("Nike")).thenReturn(List.of(deleted));
            BrandNameValidator validator = new BrandNameValidator(repository);

            assertDoesNotThrow(() -> validator.validateNotDuplicated("nike"));
            assertDoesNotThrow(() -> validator.validateNotDuplicated("Nike"));
        }
    }
}
