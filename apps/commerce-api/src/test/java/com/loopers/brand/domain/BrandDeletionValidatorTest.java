package com.loopers.brand.domain;

import com.loopers.product.domain.Product;
import com.loopers.product.domain.ProductRepository;
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

class BrandDeletionValidatorTest {

    @DisplayName("[INV-03] 삭제된 브랜드에 속한 상품은 모두 삭제된 상태다.")
    @Nested
    class RejectDeletionWithActiveProduct {

        @DisplayName("[의사결정표] 활성 상품이 하나라도 연결되어 있으면 거절한다.")
        @Test
        void throwsBrandHasProducts_whenActiveProductExists() {
            Brand brand = new Brand("브랜드");
            ProductRepository repository = mock(ProductRepository.class);
            when(repository.findAllByBrandId(brand.getId()))
                .thenReturn(List.of(new Product(brand.getId(), "상품", 1_000L)));
            BrandDeletionValidator validator = new BrandDeletionValidator(repository);

            CoreException result = assertThrows(
                CoreException.class,
                () -> validator.validateDeletable(brand)
            );

            assertThat(result.getErrorCode()).isEqualTo(ErrorCode.BRAND_HAS_PRODUCTS);
        }

        @DisplayName("[의사결정표] 연결된 상품이 없거나 모두 삭제됐으면 허용한다.")
        @Test
        void allowsDeletion_whenNoActiveProductExists() {
            Brand brand = new Brand("브랜드");
            Product deleted = new Product(brand.getId(), "상품", 1_000L);
            deleted.delete();
            ProductRepository repository = mock(ProductRepository.class);
            when(repository.findAllByBrandId(brand.getId())).thenReturn(List.of(deleted));
            BrandDeletionValidator validator = new BrandDeletionValidator(repository);

            assertDoesNotThrow(() -> validator.validateDeletable(brand));
        }

        @DisplayName("[의사결정표] 연결된 상품이 없으면 허용한다.")
        @Test
        void allowsDeletion_whenNoProductExists() {
            Brand brand = new Brand("브랜드");
            ProductRepository repository = mock(ProductRepository.class);
            when(repository.findAllByBrandId(brand.getId())).thenReturn(List.of());
            BrandDeletionValidator validator = new BrandDeletionValidator(repository);

            assertDoesNotThrow(() -> validator.validateDeletable(brand));
        }

        @DisplayName("[동등 클래스 분할] 재고가 0이어도 삭제되지 않은 상품이면 거절한다.")
        @Test
        void throwsBrandHasProducts_whenZeroStockProductExists() {
            Brand brand = new Brand("브랜드");
            Product zeroStockProduct = new Product(brand.getId(), "상품", 1_000L);
            ProductRepository repository = mock(ProductRepository.class);
            when(repository.findAllByBrandId(brand.getId())).thenReturn(List.of(zeroStockProduct));
            BrandDeletionValidator validator = new BrandDeletionValidator(repository);

            CoreException result = assertThrows(
                CoreException.class,
                () -> validator.validateDeletable(brand)
            );

            assertThat(result.getErrorCode()).isEqualTo(ErrorCode.BRAND_HAS_PRODUCTS);
        }
    }
}
