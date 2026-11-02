package com.loopers.purchaseactivity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.loopers.purchaseactivity.domain.PurchaseActivity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;

class PurchaseActivityTest {
    private static final Instant CONFIRMED_AT = Instant.parse("2026-09-07T01:00:00.123456Z");

    @Test
    void 유효한_구매_활동을_생성한다() {
        var activity = new PurchaseActivity(42, 7, 2, CONFIRMED_AT);

        assertThat(activity.orderId()).isEqualTo(42);
        assertThat(activity.productId()).isEqualTo(7);
        assertThat(activity.quantity()).isEqualTo(2);
        assertThat(activity.confirmedAt()).isEqualTo(CONFIRMED_AT);
    }

    @ParameterizedTest
    @CsvSource({"0, 7, 2", "-1, 7, 2", "42, 0, 2", "42, -1, 2", "42, 7, 0", "42, 7, -1"})
    void 주문과_상품_ID_및_수량이_양수가_아니면_거절한다(long orderId, long productId, int quantity) {
        assertThatThrownBy(() -> new PurchaseActivity(orderId, productId, quantity, CONFIRMED_AT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 확정_시각이_null이거나_마이크로초_정밀도를_초과하면_거절한다() {
        assertThatThrownBy(() -> new PurchaseActivity(42, 7, 2, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PurchaseActivity(42, 7, 2, CONFIRMED_AT.plusNanos(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
