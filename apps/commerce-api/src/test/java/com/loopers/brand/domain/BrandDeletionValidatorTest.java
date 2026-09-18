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

    @DisplayName("[R-ADMIN-02] 삭제되지 않은 상품이 연결된 브랜드는 삭제할 수 없다.")
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
    }

    @DisplayName("[R-ADMIN-03] 재고가 0인 상품도 브랜드 삭제를 막는 연결 상품에 포함된다.")
    @Nested
    class ZeroStockStillBlocksDeletion {

        @DisplayName("[경계값 분석] 재고 0인 활성 상품이 연결되어 있으면 거절한다.")
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
