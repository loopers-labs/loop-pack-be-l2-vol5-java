package com.loopers.domain.brand;

import com.loopers.support.error.CoreException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BrandServiceTest {

    private FakeBrandRepository brandRepository;
    private BrandService brandService;

    @BeforeEach
    void setUp() {
        brandRepository = new FakeBrandRepository();
        brandService = new BrandService(brandRepository);
    }

    @DisplayName("브랜드를 삭제할 때, ")
    @Nested
    class Delete {
        @DisplayName("살아 있는 브랜드면, 브랜드가 삭제된다. 상품을 함께 삭제하는 일은 BrandFacade 가 조율한다. (BRD-02 삭제 입구)")
        @Test
        void deletesBrand() {
            // arrange
            Brand brand = brandRepository.save(new Brand("브랜드", null));

            // act
            brandService.delete(brand.getId());

            // assert
            assertThat(brand.isDeleted()).isTrue();
        }

        @DisplayName("이미 삭제된 브랜드면, BRAND_NOT_FOUND 예외가 발생한다.")
        @Test
        void throwsBrandNotFound_whenBrandIsAlreadyDeleted() {
            // arrange
            Brand brand = brandRepository.save(new Brand("브랜드", null));
            brandService.delete(brand.getId());

            // act
            CoreException result = assertThrows(CoreException.class, () -> brandService.delete(brand.getId()));

            // assert
            assertThat(result.getErrorCode()).isEqualTo(BrandErrorCode.BRAND_NOT_FOUND);
        }
    }

    @DisplayName("없는 브랜드를 조회하거나 수정하면, BRAND_NOT_FOUND 예외가 발생한다.")
    @Test
    void throwsBrandNotFound_whenBrandDoesNotExist() {
        assertAll(
            () -> assertThat(assertThrows(CoreException.class, () -> brandService.getActiveBrand(999L)).getErrorCode())
                .isEqualTo(BrandErrorCode.BRAND_NOT_FOUND),
            () -> assertThat(assertThrows(CoreException.class, () -> brandService.update(999L, "브랜드", null)).getErrorCode())
                .isEqualTo(BrandErrorCode.BRAND_NOT_FOUND)
        );
    }
}
