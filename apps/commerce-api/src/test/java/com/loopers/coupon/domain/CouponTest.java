package com.loopers.coupon.domain;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CouponTest {

    private static final ZonedDateTime FROM = ZonedDateTime.parse("2026-11-13T00:00:00+09:00");
    private static final ZonedDateTime UNTIL = ZonedDateTime.parse("2026-11-30T00:00:00+09:00");

    private static CouponDraft fixed(long amount) {
        return new CouponDraft("3천원 할인", DiscountType.FIXED, amount, null, 10_000,
            CouponScope.ALL, Set.of(), ValidityType.DAYS_FROM_ISSUE, null, null, 7, false);
    }

    private static CouponDraft rate(long percent, Long max) {
        return new CouponDraft("10% 할인", DiscountType.RATE, percent, max, 0,
            CouponScope.ALL, Set.of(), ValidityType.FIXED_PERIOD, FROM, UNTIL, null, true);
    }

    private static CouponDraft withScope(CouponScope scope, Set<Long> productIds) {
        return new CouponDraft("상품 쿠폰", DiscountType.FIXED, 1_000, null, 0,
            scope, productIds, ValidityType.DAYS_FROM_ISSUE, null, null, 1, false);
    }

    private static CouponDraft withValidity(ValidityType type, ZonedDateTime from, ZonedDateTime until, Integer days) {
        return new CouponDraft("기간 쿠폰", DiscountType.FIXED, 1_000, null, 0,
            CouponScope.ALL, Set.of(), type, from, until, days, false);
    }

    @DisplayName("CPN-04 할인 규칙")
    @Nested
    class Discount {

        @DisplayName("정액 1원, 정률 1%·100%(최대 할인액 있거나 없음)는 허용한다.")
        @Test
        void acceptsBoundaries() {
            Coupon fixed = Coupon.of(1L, fixed(1));
            Coupon rateMin = Coupon.of(1L, rate(1, null));
            Coupon rateMax = Coupon.of(1L, rate(100, 5_000L));

            assertThat(fixed.getDiscountType()).isEqualTo(DiscountType.FIXED);
            assertThat(fixed.getDiscountValue()).isEqualTo(1);
            assertThat(fixed.getMaxDiscountAmount()).isNull();
            assertThat(rateMin.getDiscountValue()).isEqualTo(1);
            assertThat(rateMax.getDiscountValue()).isEqualTo(100);
            assertThat(rateMax.getMaxDiscountAmount()).isEqualTo(5_000L);
        }

        @DisplayName("정액 0원, 정률 0%·101%, 0원 최대 할인액, 정액의 최대 할인액은 거절한다.")
        @Test
        void rejectsOutOfRange() {
            assertBadRequest(() -> Coupon.of(1L, fixed(0)));
            assertBadRequest(() -> Coupon.of(1L, rate(0, null)));
            assertBadRequest(() -> Coupon.of(1L, rate(101, null)));
            assertBadRequest(() -> Coupon.of(1L, rate(10, 0L)));
            assertBadRequest(() -> Coupon.of(1L, new CouponDraft("정액", DiscountType.FIXED, 1_000, 500L, 0,
                CouponScope.ALL, Set.of(), ValidityType.DAYS_FROM_ISSUE, null, null, 1, false)));
        }

        @DisplayName("최소 주문 금액이 음수면 거절한다.")
        @Test
        void rejectsNegativeMinOrderAmount() {
            assertBadRequest(() -> Coupon.of(1L, new CouponDraft("정액", DiscountType.FIXED, 1_000, null, -1,
                CouponScope.ALL, Set.of(), ValidityType.DAYS_FROM_ISSUE, null, null, 1, false)));
        }
    }

    @DisplayName("CPN-04 쿠폰 이름은 1–100자(앞뒤 공백 제거)다. 공백뿐이거나 101자면 거절한다.")
    @Test
    void nameRule() {
        CouponDraft base = fixed(1_000);
        Coupon longest = Coupon.of(1L, new CouponDraft(" " + "가".repeat(100) + " ", base.discountType(), base.discountValue(), null,
            base.minOrderAmount(), base.scope(), Set.of(), base.validityType(), null, null, base.validDays(), false));

        assertThat(longest.getName()).hasSize(100);
        assertBadRequest(() -> Coupon.of(1L, new CouponDraft("  ", base.discountType(), base.discountValue(), null,
            base.minOrderAmount(), base.scope(), Set.of(), base.validityType(), null, null, base.validDays(), false)));
        assertBadRequest(() -> Coupon.of(1L, new CouponDraft("가".repeat(101), base.discountType(), base.discountValue(), null,
            base.minOrderAmount(), base.scope(), Set.of(), base.validityType(), null, null, base.validDays(), false)));
    }

    @DisplayName("CPN-04 정률의 최대 할인액 1원은 허용한다(경계).")
    @Test
    void rateMaxDiscountOfOneIsAllowed() {
        assertThat(Coupon.of(1L, rate(10, 1L)).getMaxDiscountAmount()).isEqualTo(1L);
    }

    @DisplayName("적용 상품 id에 빈 값(null)이 있으면 400이다 (500이 아니라).")
    @Test
    void rejectsNullProductId() {
        java.util.Set<Long> withNull = new java.util.HashSet<>(java.util.Arrays.asList(10L, null));
        assertBadRequest(() -> withScope(CouponScope.PRODUCTS, withNull));
    }

    @DisplayName("CPN-05 적용 범위")
    @Nested
    class Scope {

        @DisplayName("ALL은 적용 상품 없이, PRODUCTS는 1개 이상으로 만든다.")
        @Test
        void acceptsMatchingProducts() {
            assertThat(Coupon.of(1L, withScope(CouponScope.ALL, Set.of())).getApplicableProductIds()).isEmpty();
            assertThat(Coupon.of(1L, withScope(CouponScope.PRODUCTS, Set.of(10L, 11L))).getApplicableProductIds())
                .containsExactlyInAnyOrder(10L, 11L);
        }

        @DisplayName("ALL에 적용 상품이 있거나, PRODUCTS에 적용 상품이 없으면 거절한다.")
        @Test
        void rejectsMismatch() {
            assertBadRequest(() -> Coupon.of(1L, withScope(CouponScope.ALL, Set.of(10L))));
            assertBadRequest(() -> Coupon.of(1L, withScope(CouponScope.PRODUCTS, Set.of())));
        }
    }

    @DisplayName("CPN-06 유효 기간")
    @Nested
    class Validity {

        @DisplayName("FIXED_PERIOD는 시작 < 끝이고 일수가 없어야, DAYS_FROM_ISSUE는 일수 ≥ 1이고 시각이 없어야 한다.")
        @Test
        void rejectsInvalidCombinations() {
            assertBadRequest(() -> Coupon.of(1L, withValidity(ValidityType.FIXED_PERIOD, UNTIL, FROM, null)));
            assertBadRequest(() -> Coupon.of(1L, withValidity(ValidityType.FIXED_PERIOD, FROM, FROM, null)));
            assertBadRequest(() -> Coupon.of(1L, withValidity(ValidityType.FIXED_PERIOD, FROM, null, null)));
            assertBadRequest(() -> Coupon.of(1L, withValidity(ValidityType.FIXED_PERIOD, FROM, UNTIL, 7)));
            assertBadRequest(() -> Coupon.of(1L, withValidity(ValidityType.DAYS_FROM_ISSUE, null, null, 0)));
            assertBadRequest(() -> Coupon.of(1L, withValidity(ValidityType.DAYS_FROM_ISSUE, null, null, null)));
            assertBadRequest(() -> Coupon.of(1L, withValidity(ValidityType.DAYS_FROM_ISSUE, FROM, UNTIL, 7)));
        }

        @DisplayName("CPN-07 만료 시각: FIXED_PERIOD는 끝 시각, DAYS_FROM_ISSUE는 발급 시각 + 일수다.")
        @Test
        void expiresAtByType() {
            ZonedDateTime issuedAt = ZonedDateTime.parse("2026-11-13T10:00:05+09:00");

            assertThat(Coupon.of(1L, withValidity(ValidityType.FIXED_PERIOD, FROM, UNTIL, null)).expiresAtWhenIssuedAt(issuedAt))
                .isEqualTo(UNTIL);
            assertThat(Coupon.of(1L, withValidity(ValidityType.DAYS_FROM_ISSUE, null, null, 7)).expiresAtWhenIssuedAt(issuedAt))
                .isEqualTo(ZonedDateTime.parse("2026-11-20T10:00:05+09:00"));
        }
    }

    private static void assertBadRequest(Runnable creation) {
        assertThatThrownBy(creation::run)
            .isInstanceOfSatisfying(CoreException.class, e -> assertThat(e.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST));
    }
}
