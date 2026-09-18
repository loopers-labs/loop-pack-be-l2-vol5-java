package com.loopers.product.domain;

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

class ProductNameValidatorTest {

    @DisplayName("[P-ADMIN-02] 같은 브랜드의 삭제되지 않은 상품끼리는 이름이 달라야 한다.")
    @Nested
    class ValidateProductNameDuplication {

        @DisplayName("[의사결정표] 같은 브랜드의 활성 상품과 이름이 같으면 중복으로 거절한다.")
        @Test
        void throwsDuplicateProductName_whenActiveProductHasSameName() {
            ProductRepository repository = mock(ProductRepository.class);
            Product existing = new Product(1L, "Nike", 1_000L);
            when(repository.findAllByBrandIdAndName(1L, "Nike")).thenReturn(List.of(existing));
            ProductNameValidator validator = new ProductNameValidator(repository);

            CoreException result = assertThrows(
                CoreException.class,
                () -> validator.validateNotDuplicated(1L, "Nike")
            );

            assertThat(result.getErrorCode()).isEqualTo(ErrorCode.DUPLICATE_PRODUCT_NAME);
        }

        @DisplayName("[의사결정표] 다른 대소문자이거나 삭제된 상품과 같은 이름이면 허용한다.")
        @Test
        void allowsName_whenCaseDiffersOrMatchedProductIsDeleted() {
            ProductRepository repository = mock(ProductRepository.class);
            Product deleted = new Product(1L, "Nike", 1_000L);
            deleted.delete();
            when(repository.findAllByBrandIdAndName(1L, "nike")).thenReturn(List.of());
            when(repository.findAllByBrandIdAndName(1L, "Nike")).thenReturn(List.of(deleted));
            ProductNameValidator validator = new ProductNameValidator(repository);

            assertDoesNotThrow(() -> validator.validateNotDuplicated(1L, "nike"));
            assertDoesNotThrow(() -> validator.validateNotDuplicated(1L, "Nike"));
        }
    }
}
