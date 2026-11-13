package com.loopers.domain.promotion;

import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.Map;

import static com.loopers.support.ErrorAssertions.assertThrowsErrorType;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** AG-07 포함 개념 선착순 쿠폰 발급 설정 (TB-16 coupon_issue_promotion). */
class CouponIssuePromotionModelTest {

    private static final Map<String, Object> SPEC = Map.of("discountType", "FIXED", "discountValue", 3000);

    @DisplayName("받은 promotionId·한도·쿠폰 속성 그대로 만든다.")
    @Test
    void create_keepsValues() {
        CouponIssuePromotionModel couponIssue = CouponIssuePromotionModel.create(1L, 100, 1, SPEC);

        assertThat(couponIssue.getPromotionId()).isEqualTo(1L);
        assertThat(couponIssue.getTotalQuantity()).isEqualTo(100);
        assertThat(couponIssue.getPerUserLimit()).isEqualTo(1);
        assertThat(couponIssue.getCouponSpec()).isEqualTo(SPEC);
    }

    @DisplayName("promotionId 가 없으면 INVALID_PROMOTION 으로 만들지 않는다.")
    @Test
    void create_rejectsMissingPromotionId() {
        assertThrowsErrorType(() -> CouponIssuePromotionModel.create(null, 100, 1, SPEC), ErrorType.INVALID_PROMOTION);
    }

    @DisplayName("발급 한도는 1 부터 받고, 1 미만이면 INVALID_PROMOTION 이다.")
    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void create_rejectsTotalQuantityBelowOne(int totalQuantity) {
        assertThat(CouponIssuePromotionModel.create(1L, 1, 1, SPEC).getTotalQuantity()).isEqualTo(1);
        assertThrowsErrorType(() -> CouponIssuePromotionModel.create(1L, totalQuantity, 1, SPEC), ErrorType.INVALID_PROMOTION);
    }

    @DisplayName("1인당 한도는 1 만 받는다. 발급 내역이 사용자·프로모션당 한 행이기 때문이다.")
    @ParameterizedTest
    @ValueSource(ints = {0, -1, 2, 10})
    void create_rejectsPerUserLimitOtherThanOne(int perUserLimit) {
        assertThrowsErrorType(() -> CouponIssuePromotionModel.create(1L, 10, perUserLimit, SPEC), ErrorType.INVALID_PROMOTION);
    }

    @DisplayName("쿠폰 속성이 없거나 비어 있으면 INVALID_PROMOTION 이다.")
    @Test
    void create_rejectsEmptySpec() {
        assertThrowsErrorType(() -> CouponIssuePromotionModel.create(1L, 10, 1, null), ErrorType.INVALID_PROMOTION);
        assertThrowsErrorType(() -> CouponIssuePromotionModel.create(1L, 10, 1, Map.of()), ErrorType.INVALID_PROMOTION);
    }

    @DisplayName("만든 뒤 넘겨준 Map 을 바꿔도 쿠폰 속성은 바뀌지 않고, 꺼낸 Map 으로도 바꿀 수 없다.")
    @Test
    void couponSpec_isIsolated() {
        Map<String, Object> source = new HashMap<>(SPEC);
        CouponIssuePromotionModel couponIssue = CouponIssuePromotionModel.create(1L, 10, 1, source);

        source.put("discountValue", 9999);

        assertThat(couponIssue.getCouponSpec()).isEqualTo(SPEC);
        assertThatThrownBy(() -> couponIssue.getCouponSpec().put("x", 1))
            .isInstanceOf(UnsupportedOperationException.class);
    }
}
