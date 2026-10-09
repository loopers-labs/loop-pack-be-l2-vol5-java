package com.loopers.application.product;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandErrorCode;
import com.loopers.domain.brand.BrandService;
import com.loopers.domain.brand.FakeBrandRepository;
import com.loopers.domain.product.FakeProductRepository;
import com.loopers.domain.product.ProductService;
import com.loopers.support.error.CoreException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 상품 생성의 BRD-02 생성 입구는 브랜드와 상품 두 도메인의 협력이라 Facade 에서 확인함 (설계 4.4) */
class AdminProductFacadeTest {

    private FakeBrandRepository brandRepository;
    private FakeProductRepository productRepository;
    private AdminProductFacade adminProductFacade;

    @BeforeEach
    void setUp() {
        brandRepository = new FakeBrandRepository();
        productRepository = new FakeProductRepository();
        adminProductFacade = new AdminProductFacade(new ProductService(productRepository, Clock.systemDefaultZone()), new BrandService(brandRepository));
    }

    @DisplayName("살아 있는 브랜드면, 재고 0 인 상품이 그 브랜드로 저장된다.")
    @Test
    void createsProduct() {
        Brand brand = brandRepository.save(new Brand("브랜드", null));

        AdminProductInfo result = adminProductFacade.createProduct(brand.getId(), "상품", 1_000L);

        assertAll(
            () -> assertThat(result.stock()).isZero(),
            () -> assertThat(result.brandId()).isEqualTo(brand.getId())
        );
    }

    @DisplayName("없거나 삭제된 브랜드면, BRAND_NOT_FOUND 예외가 발생하고 상품이 저장되지 않는다. (BRD-02)")
    @Test
    void throwsBrandNotFound_andSavesNothing_whenBrandIsMissingOrDeleted() {
        // arrange
        Brand deleted = brandRepository.save(new Brand("브랜드", null));
        deleted.delete();

        // act
        CoreException missing = assertThrows(CoreException.class, () -> adminProductFacade.createProduct(999L, "상품", 1_000L));
        CoreException deletedResult = assertThrows(CoreException.class, () -> adminProductFacade.createProduct(deleted.getId(), "상품", 1_000L));

        // assert
        assertAll(
            () -> assertThat(missing.getErrorCode()).isEqualTo(BrandErrorCode.BRAND_NOT_FOUND),
            () -> assertThat(deletedResult.getErrorCode()).isEqualTo(BrandErrorCode.BRAND_NOT_FOUND),
            () -> assertThat(productRepository.count()).isZero()
        );
    }
}
