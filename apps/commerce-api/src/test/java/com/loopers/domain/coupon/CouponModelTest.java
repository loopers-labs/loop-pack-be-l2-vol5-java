package com.loopers.domain.coupon;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class CouponModelTest {
    private static final Instant ISSUED_AT = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void preservesOwnerCampaignIssueTimeAndBenefitSnapshot() {
        CouponModel coupon = new CouponModel(1L, 2L, ISSUED_AT, " 5,000원 할인권 ");

        assertThat(coupon.getUserId()).isEqualTo(1L);
        assertThat(coupon.getCampaignId()).isEqualTo(2L);
        assertThat(coupon.getIssuedAt()).isEqualTo(ISSUED_AT);
        assertThat(coupon.getBenefitDescription()).isEqualTo(" 5,000원 할인권 ");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0, -1})
    void rejectsInvalidUserId(Long userId) {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new CouponModel(userId, 2L, ISSUED_AT, "할인권"));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0, -1})
    void rejectsInvalidCampaignId(Long campaignId) {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new CouponModel(1L, campaignId, ISSUED_AT, "할인권"));
    }

    @Test
    void rejectsMissingIssueTime() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new CouponModel(1L, 2L, null, "할인권"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void rejectsMissingBenefit(String benefit) {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new CouponModel(1L, 2L, ISSUED_AT, benefit));
    }

    @Test
    void checksBenefitLengthInCodePoints() {
        String benefit = "😀".repeat(1000);

        assertThat(new CouponModel(1L, 2L, ISSUED_AT, benefit).getBenefitDescription()).isEqualTo(benefit);
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new CouponModel(1L, 2L, ISSUED_AT, benefit + "가"));
    }
}
