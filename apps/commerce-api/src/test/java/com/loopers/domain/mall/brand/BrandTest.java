package com.loopers.domain.mall.brand;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.loopers.domain.mall.product.Product;
import com.loopers.domain.support.error.DomainErrorCode;
import com.loopers.domain.support.error.DomainException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BrandTest {
    @DisplayName("이름의 앞뒤 공백을 제거하고 브랜드를 생성한다")
    @Test
    void createsBrand_withTrimmedName() {
        Brand brand = Brand.create("  LOOP  ", null);

        assertThat(brand.getName()).isEqualTo("LOOP");
        assertThat(brand.getDescription()).isNull();
    }

    @DisplayName("잘못된 수정은 기존 상태를 유지한다")
    @Test
    void keepsState_whenUpdateFails() {
        Brand brand = Brand.create("기존", "설명");

        assertThatThrownBy(() -> brand.update(" ", "변경"))
            .isInstanceOf(DomainException.class)
            .extracting("errorCode")
            .isEqualTo(DomainErrorCode.INVALID_NAME);
        assertThat(brand.getName()).isEqualTo("기존");
        assertThat(brand.getDescription()).isEqualTo("설명");
    }

    @DisplayName("삭제된 브랜드는 수정할 수 없다")
    @Test
    void rejectsUpdate_whenDeleted() {
        Brand brand = Brand.restore(1L, "브랜드", null, true, Instant.now());

        assertThatThrownBy(() -> brand.update("변경", null))
            .isInstanceOf(DomainException.class)
            .extracting("errorCode")
            .isEqualTo(DomainErrorCode.DELETED_BRAND);
    }

    @DisplayName("브랜드를 삭제하면 연결된 미삭제 상품 전체도 함께 삭제된다")
    @Test
    void deletesBrand_andCascadesToUndeletedProducts() {
        Product active = Product.restore(1L, 1L, "상품1", null, 1_000L, 5, false, Instant.now());
        Product outOfStock = Product.restore(2L, 1L, "상품2", null, 1_000L, 0, false, Instant.now());
        Brand brand = Brand.restoreForDeletion(1L, "브랜드", null, false, Instant.now(), List.of(active, outOfStock));

        brand.delete();

        assertThat(brand.isDeleted()).isTrue();
        assertThat(active.isDeleted()).isTrue();
        assertThat(outOfStock.isDeleted()).isTrue();
    }

    @DisplayName("연결 상품이 없는 브랜드도 정상적으로 삭제된다")
    @Test
    void deletesBrand_withNoProducts() {
        Brand brand = Brand.restoreForDeletion(1L, "브랜드", null, false, Instant.now(), List.of());

        brand.delete();

        assertThat(brand.isDeleted()).isTrue();
    }

    @DisplayName("이미 삭제된 상품은 다시 처리하지 않고 상태를 유지한다")
    @Test
    void keepsAlreadyDeletedProductState_whenBrandDeleted() {
        Product alreadyDeleted = Product.restore(1L, 1L, "상품1", null, 1_000L, 5, true, Instant.now());
        Product active = Product.restore(2L, 1L, "상품2", null, 1_000L, 5, false, Instant.now());
        Brand brand = Brand.restoreForDeletion(1L, "브랜드", null, false, Instant.now(),
            List.of(alreadyDeleted, active));

        brand.delete();

        assertThat(brand.isDeleted()).isTrue();
        assertThat(alreadyDeleted.isDeleted()).isTrue();
        assertThat(active.isDeleted()).isTrue();
    }

    @DisplayName("상품 목록을 조회하지 않은 상태는 구조적으로 거부한다")
    @Test
    void rejectsRestoreForDeletion_whenProductsIsNull() {
        assertThatThrownBy(() -> Brand.restoreForDeletion(1L, "브랜드", null, false, Instant.now(), null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @DisplayName("이미 삭제된 브랜드는 다시 삭제할 수 없다")
    @Test
    void rejectsDelete_whenAlreadyDeleted() {
        Brand brand = Brand.restoreForDeletion(1L, "브랜드", null, true, Instant.now(), List.of());

        assertThatThrownBy(brand::delete)
            .isInstanceOf(DomainException.class)
            .extracting("errorCode")
            .isEqualTo(DomainErrorCode.DELETED_BRAND);
    }
}
