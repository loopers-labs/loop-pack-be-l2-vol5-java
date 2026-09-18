package com.loopers.domain.product;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandErrorCode;
import com.loopers.domain.brand.BrandService;
import com.loopers.domain.brand.FakeBrandRepository;
import com.loopers.support.error.CoreException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProductServiceTest {

    private FakeBrandRepository brandRepository;
    private FakeProductRepository productRepository;
    private ProductService productService;

    @BeforeEach
    void setUp() {
        brandRepository = new FakeBrandRepository();
        productRepository = new FakeProductRepository();
        productService = new ProductService(productRepository, new BrandService(brandRepository));
    }

    @DisplayName("상품을 생성할 때, ")
    @Nested
    class Create {
        @DisplayName("살아 있는 브랜드면, 재고 0 인 상품이 저장된다.")
        @Test
        void createsProductWithZeroStock() {
            // arrange
            Brand brand = brandRepository.save(new Brand("브랜드", null));

            // act
            Product product = productService.create(brand.getId(), "상품", 1_000L);

            // assert
            assertAll(
                () -> assertThat(product.getStock()).isZero(),
                () -> assertThat(productService.getActiveProductWithBrand(product.getId()).brandId()).isEqualTo(brand.getId())
            );
        }

        @DisplayName("없거나 삭제된 브랜드면, BRAND_NOT_FOUND 예외가 발생하고 상품이 저장되지 않는다.")
        @Test
        void throwsBrandNotFound_andSavesNothing_whenBrandIsMissingOrDeleted() {
            // arrange
            Brand deleted = brandRepository.save(new Brand("브랜드", null));
            deleted.delete();

            // act
            CoreException missing = assertThrows(CoreException.class, () -> productService.create(999L, "상품", 1_000L));
            CoreException deletedResult = assertThrows(CoreException.class, () -> productService.create(deleted.getId(), "상품", 1_000L));

            // assert
            assertAll(
                () -> assertThat(missing.getErrorCode()).isEqualTo(BrandErrorCode.BRAND_NOT_FOUND),
                () -> assertThat(deletedResult.getErrorCode()).isEqualTo(BrandErrorCode.BRAND_NOT_FOUND),
                () -> assertThat(productRepository.count()).isZero()
            );
        }
    }

    @DisplayName("삭제한 상품은 조회 · 수정 · 재고 변경 · 삭제에서 PRODUCT_NOT_FOUND 예외가 발생한다.")
    @Test
    void deletedProductIsNotFound() {
        // arrange
        Brand brand = brandRepository.save(new Brand("브랜드", null));
        Long productId = productService.create(brand.getId(), "상품", 1_000L).getId();
        productService.delete(productId);

        // act & assert
        assertAll(
            () -> assertThat(assertThrows(CoreException.class, () -> productService.getActiveProduct(productId)).getErrorCode())
                .isEqualTo(ProductErrorCode.PRODUCT_NOT_FOUND),
            () -> assertThat(assertThrows(CoreException.class, () -> productService.update(productId, "새 상품", 2_000L)).getErrorCode())
                .isEqualTo(ProductErrorCode.PRODUCT_NOT_FOUND),
            () -> assertThat(assertThrows(CoreException.class, () -> productService.changeStock(productId, 10)).getErrorCode())
                .isEqualTo(ProductErrorCode.PRODUCT_NOT_FOUND),
            () -> assertThat(assertThrows(CoreException.class, () -> productService.delete(productId)).getErrorCode())
                .isEqualTo(ProductErrorCode.PRODUCT_NOT_FOUND)
        );
    }
}
