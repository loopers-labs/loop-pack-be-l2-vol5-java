package com.loopers.domain.product;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandErrorCode;
import com.loopers.domain.brand.FakeBrandRepository;
import com.loopers.support.error.CoreException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        productService = new ProductService(productRepository, Clock.systemDefaultZone());
    }

    @DisplayName("살아 있는 상품 식별자를 물으면, 없거나 삭제된 상품은 빠지고 예외가 발생하지 않는다. (설계 6.4)")
    @Test
    void returnsOnlyActiveProductIds() {
        // arrange
        Brand brand = brandRepository.save(new Brand("브랜드", null));
        Product active = productService.create(brand, "판매 중", 1_000L);
        Product deleted = productService.create(brand, "판매 종료", 1_000L);
        deleted.delete();

        // act
        Set<Long> result = productService.getActiveProductIds(List.of(active.getId(), deleted.getId(), 999L));

        // assert
        assertThat(result).containsExactly(active.getId());
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
            Product product = productService.create(brand, "상품", 1_000L);

            // assert
            assertAll(
                () -> assertThat(product.getStock()).isZero(),
                () -> assertThat(productService.getActiveProductWithBrand(product.getId()).brandId()).isEqualTo(brand.getId())
            );
        }

        // 없는 브랜드는 조율하는 AdminProductFacade 가 BrandService 로 거절함 (AdminProductFacadeTest)
        @DisplayName("삭제된 브랜드를 받으면, Product 생성자가 BRAND_NOT_FOUND 예외를 던지고 상품이 저장되지 않는다. (BRD-02)")
        @Test
        void throwsBrandNotFound_andSavesNothing_whenBrandIsDeleted() {
            // arrange
            Brand deleted = brandRepository.save(new Brand("브랜드", null));
            deleted.delete();

            // act
            CoreException result = assertThrows(CoreException.class, () -> productService.create(deleted, "상품", 1_000L));

            // assert
            assertAll(
                () -> assertThat(result.getErrorCode()).isEqualTo(BrandErrorCode.BRAND_NOT_FOUND),
                () -> assertThat(productRepository.count()).isZero()
            );
        }
    }

    @DisplayName("삭제한 상품은 조회 · 수정 · 재고 변경 · 삭제에서 PRODUCT_NOT_FOUND 예외가 발생한다.")
    @Test
    void deletedProductIsNotFound() {
        // arrange
        Brand brand = brandRepository.save(new Brand("브랜드", null));
        Long productId = productService.create(brand, "상품", 1_000L).getId();
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

    @DisplayName("상품들을 잠가 조회할 때, ")
    @Nested
    class GetActiveProductsForUpdate {
        @DisplayName("받은 순서대로 돌려준다.")
        @Test
        void returnsProductsInRequestedOrder() {
            // arrange
            Brand brand = brandRepository.save(new Brand("브랜드", null));
            Product first = productService.create(brand, "먼저 만든 상품", 1_000L);
            Product second = productService.create(brand, "나중에 만든 상품", 1_000L);

            // act
            Map<Long, Product> result = productService.getActiveProductsForUpdate(List.of(second.getId(), first.getId()));

            // assert
            assertThat(result.keySet()).containsExactly(second.getId(), first.getId());
        }

        @DisplayName("없거나 삭제된 상품이 여럿이면, 식별자 순서가 아니라 받은 순서에서 처음 것을 알리는 PRODUCT_NOT_FOUND 예외가 발생한다. (설계 D-16)")
        @Test
        void throwsProductNotFoundForFirstMissingInRequestedOrder() {
            // arrange
            Brand brand = brandRepository.save(new Brand("브랜드", null));
            Product deletedFirst = productService.create(brand, "먼저 만든 상품", 1_000L);
            Product deletedSecond = productService.create(brand, "나중에 만든 상품", 1_000L);
            deletedFirst.delete();
            deletedSecond.delete();

            // act
            CoreException result = assertThrows(CoreException.class,
                () -> productService.getActiveProductsForUpdate(List.of(deletedSecond.getId(), deletedFirst.getId())));

            // assert
            assertAll(
                () -> assertThat(result.getErrorCode()).isEqualTo(ProductErrorCode.PRODUCT_NOT_FOUND),
                () -> assertThat(result.getDetail()).isEqualTo(ProductErrorDetail.of(deletedSecond.getId()))
            );
        }
    }
}
