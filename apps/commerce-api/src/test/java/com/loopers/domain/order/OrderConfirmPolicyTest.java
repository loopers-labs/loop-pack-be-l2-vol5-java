package com.loopers.domain.order;

import com.loopers.domain.product.ProductModel;
import com.loopers.domain.product.ProductSnapshot;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderConfirmPolicyTest {

    private final OrderConfirmPolicy policy = new OrderConfirmPolicy();

    /** 단위 테스트에는 DB가 없으므로 식별자를 직접 넣는다. */
    private static ProductModel product(long id, long price, int stock) {
        ProductModel product = new ProductModel(1L, "상품" + id, price, stock);
        ReflectionTestUtils.setField(product, "id", id);
        return product;
    }

    /** 상품 1번 5개(단가 1,000)를 담은 DRAFT 주문. */
    private static OrderModel orderOfFive() {
        return OrderModel.create(
            7L,
            OrderLines.of(List.of(new OrderLines.Line(1L, 5))),
            Map.of(1L, new ProductSnapshot(1L, "상품1", com.loopers.domain.common.Money.of(1_000)))
        );
    }

    @DisplayName("ORD-03 모든 품목의 상품이 팔 수 있고, 단가가 같고, 재고가 충분하면 확정할 수 있다.")
    @Test
    void passesWhenAllItemsAreConfirmable() {
        // act & assert
        assertThatCode(() -> policy.check(orderOfFive(), List.of(product(1L, 1_000, 5))))
            .doesNotThrowAnyException();
    }

    @DisplayName("ORD-03 확정 전에 상품이 삭제됐으면 409다.")
    @Test
    void rejectsDeletedProduct() {
        // arrange
        ProductModel deleted = product(1L, 1_000, 5);
        deleted.delete();

        // act & assert
        assertThatThrownBy(() -> policy.check(orderOfFive(), List.of(deleted)))
            .isInstanceOf(CoreException.class)
            .extracting("errorType").isEqualTo(ErrorType.CONFLICT);
    }

    @DisplayName("ORD-03 품목의 상품을 찾을 수 없으면 409다.")
    @Test
    void rejectsMissingProduct() {
        // act & assert
        assertThatThrownBy(() -> policy.check(orderOfFive(), List.of()))
            .isInstanceOf(CoreException.class)
            .extracting("errorType").isEqualTo(ErrorType.CONFLICT);
    }

    @DisplayName("ORD-03·P-02 확정 전에 가격이 바뀌었으면(1,000 → 1,200) 409다.")
    @Test
    void rejectsChangedPrice() {
        // act & assert
        assertThatThrownBy(() -> policy.check(orderOfFive(), List.of(product(1L, 1_200, 5))))
            .isInstanceOf(CoreException.class)
            .extracting("errorType").isEqualTo(ErrorType.CONFLICT);
    }

    @DisplayName("ORD-03 재고가 품목 수량보다 적으면(5개 주문, 재고 4개) 409이고, 재고는 그대로다.")
    @Test
    void rejectsInsufficientStock_withoutChangingStock() {
        // arrange
        ProductModel product = product(1L, 1_000, 4);

        // act & assert
        assertThatThrownBy(() -> policy.check(orderOfFive(), List.of(product)))
            .isInstanceOf(CoreException.class)
            .extracting("errorType").isEqualTo(ErrorType.CONFLICT);
        assertThat(product.getStock()).isEqualTo(4);
    }
}
