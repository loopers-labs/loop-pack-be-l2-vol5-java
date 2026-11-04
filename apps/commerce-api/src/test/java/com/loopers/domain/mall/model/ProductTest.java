package com.loopers.domain.mall.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.loopers.domain.support.error.DomainErrorCode;
import com.loopers.domain.support.error.DomainException;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ProductTest {
    @DisplayName("차감 후 관리자 설정 수량에 반환하고 삭제 상품도 삭제 상태를 보존한다")
    @Test
    void restoresStock_preservingDeletedState() {
        Product product = Product.create(1L, "상품", null, 1_000L, 5);
        product.decreaseStock(2);
        product.setStock(10);
        product.delete();

        product.restoreStock(2);

        assertThat(product.getStock()).isEqualTo(12);
        assertThat(product.isDeleted()).isTrue();
    }

    @DisplayName("재고 반환이 범위를 넘으면 상품의 수량과 삭제 상태를 유지한다")
    @Test
    void keepsState_whenRestorationOverflows() {
        Product product = Product.restore(1L, 1L, "상품", null, 1_000L, Integer.MAX_VALUE, true, Instant.now());

        assertThatThrownBy(() -> product.restoreStock(1)).isInstanceOf(DomainException.class)
            .extracting("errorCode").isEqualTo(DomainErrorCode.CALCULATION_OVERFLOW);
        assertThat(product.getStock()).isEqualTo(Integer.MAX_VALUE);
        assertThat(product.isDeleted()).isTrue();
    }
    @DisplayName("상품 생성 시 이름을 정규화하고 가격과 재고를 보관한다")
    @Test
    void createsProduct() {
        Product product = Product.create(1L, " 상품 ", null, 1_000L, 5);

        assertThat(product.getName()).isEqualTo("상품");
        assertThat(product.getPrice()).isEqualTo(1_000L);
        assertThat(product.getStock()).isEqualTo(5);
    }

    @DisplayName("잘못된 가격 수정은 상품 정보를 유지한다")
    @Test
    void keepsState_whenUpdateFails() {
        Product product = Product.create(1L, "기존", "설명", 1_000L, 5);

        assertThatThrownBy(() -> product.update("변경", null, 0L))
            .isInstanceOf(DomainException.class)
            .extracting("errorCode")
            .isEqualTo(DomainErrorCode.NON_POSITIVE_MONEY);
        assertThat(product.getName()).isEqualTo("기존");
        assertThat(product.getDescription()).isEqualTo("설명");
        assertThat(product.getPrice()).isEqualTo(1_000L);
    }

    @DisplayName("삭제 상품의 정보와 재고는 변경할 수 없다")
    @Test
    void rejectsChanges_whenDeleted() {
        Product product = Product.restore(1L, 1L, "상품", null, 1_000L, 5, true, Instant.now());

        assertThatThrownBy(() -> product.setStock(0))
            .isInstanceOf(DomainException.class)
            .extracting("errorCode")
            .isEqualTo(DomainErrorCode.DELETED_PRODUCT);
        assertThat(product.getStock()).isEqualTo(5);
    }
}
