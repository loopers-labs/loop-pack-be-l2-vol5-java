package com.loopers.domain.product;

import com.loopers.domain.brand.BrandId;
import com.loopers.domain.common.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductTest {
    private Product product() {
        return Product.create(new BrandId(1), "상품", new Money(100), new Stock(5));
    }

    @Test
    @DisplayName("상품 변경과 재고 차감은 브랜드를 유지하고 부족한 차감은 기존 재고를 유지한다")
    void changesAndDecreasesStock() {
        Product product = product();
        product.change("새 상품", new Money(200));
        product.decreaseStock(2);
        assertThat(product.getBrandId()).isEqualTo(new BrandId(1));
        assertThat(product.getPrice()).isEqualTo(new Money(200));
        assertThat(product.getStock()).isEqualTo(new Stock(3));
        assertThatThrownBy(() -> product.decreaseStock(4)).isInstanceOf(IllegalStateException.class);
        assertThat(product.getStock()).isEqualTo(new Stock(3));
    }

    @Test
    @DisplayName("삭제된 상품의 정보 변경과 재고 설정과 차감은 거절한다")
    void rejectsChangesAfterDeletion() {
        Product product = product();
        product.delete();
        assertThatThrownBy(() -> product.change("수정", new Money(1))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> product.setStock(new Stock(1))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> product.decreaseStock(1)).isInstanceOf(IllegalStateException.class);
        assertThat(product.getName()).isEqualTo("상품");
        assertThat(product.getStock()).isEqualTo(new Stock(5));
    }

    @Test
    @DisplayName("잘못된 상품 이름은 생성과 변경에서 거절하고 기존 상태를 유지한다")
    void rejectsInvalidName() {
        assertThatThrownBy(() -> Product.create(new BrandId(1), " ", new Money(0), new Stock(0)))
            .isInstanceOf(IllegalArgumentException.class);
        Product product = product();
        assertThatThrownBy(() -> product.change("가".repeat(101), new Money(1)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(product.getName()).isEqualTo("상품");
        assertThat(product.getPrice()).isEqualTo(new Money(100));
    }

    @Test
    @DisplayName("복원은 상품 ID와 브랜드와 금액과 재고와 삭제 상태를 유지한다")
    void restoresState() {
        Product restored = Product.restore(new ProductId(2), new BrandId(1), "상품", new Money(0), new Stock(0), true);
        assertThat(restored.getId()).isEqualTo(new ProductId(2));
        assertThat(restored.isDeleted()).isTrue();
        assertThat(restored.getPrice()).isEqualTo(new Money(0));
    }
}
