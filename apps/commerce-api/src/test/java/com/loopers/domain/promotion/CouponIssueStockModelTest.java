package com.loopers.domain.promotion;

import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.loopers.support.ErrorAssertions.assertThrowsErrorType;
import static org.assertj.core.api.Assertions.assertThat;

/** AG-07 포함 개념 쿠폰 발급 남은 수량 (TB-17 coupon_issue_stock). */
class CouponIssueStockModelTest {

    @DisplayName("남은 수량은 발급 한도로 시작한다.")
    @Test
    void create_startsWithTotalQuantity() {
        CouponIssueStockModel stock = CouponIssueStockModel.create(1L, 100);

        assertThat(stock.getPromotionId()).isEqualTo(1L);
        assertThat(stock.getRemaining()).isEqualTo(100);
    }

    @DisplayName("promotionId 가 없거나 발급 한도가 1 미만이면 INVALID_PROMOTION 이다.")
    @Test
    void create_rejectsInvalid() {
        assertThrowsErrorType(() -> CouponIssueStockModel.create(null, 100), ErrorType.INVALID_PROMOTION);
        assertThrowsErrorType(() -> CouponIssueStockModel.create(1L, 0), ErrorType.INVALID_PROMOTION);
    }
}
