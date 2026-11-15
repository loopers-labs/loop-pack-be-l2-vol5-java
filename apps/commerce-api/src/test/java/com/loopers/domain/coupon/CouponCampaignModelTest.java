package com.loopers.domain.coupon;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class CouponCampaignModelTest {
    private static final Instant END_AT = Instant.parse("2026-01-02T00:00:00Z");

    @Test
    void startsWithNoIssuedCouponsAndPreservesCampaignInformation() {
        CouponCampaignModel campaign = new CouponCampaignModel(" 행사 ", "5,000원 할인권", true, 5, END_AT);

        assertThat(campaign.getName()).isEqualTo("행사");
        assertThat(campaign.getBenefitDescription()).isEqualTo("5,000원 할인권");
        assertThat(campaign.isActive()).isTrue();
        assertThat(campaign.getTotalQuota()).isEqualTo(5);
        assertThat(campaign.getIssuedCount()).isZero();
        assertThat(campaign.getEndAt()).isEqualTo(END_AT);
    }

    @Test
    void rejectsMissingEndAt() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new CouponCampaignModel("행사", "할인권", true, 5, null))
            .withMessage("행사 종료 시각은 필수입니다.");
    }

    @Test
    void rejectsNegativeQuota() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new CouponCampaignModel("행사", "할인권", true, -1, END_AT));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void rejectsMissingName(String name) {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new CouponCampaignModel(name, "할인권", true, 5, END_AT));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void rejectsMissingBenefit(String benefit) {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new CouponCampaignModel("행사", benefit, true, 5, END_AT));
    }

    @Test
    void checksTextLengthsInCodePoints() {
        String name = "😀".repeat(100);
        String benefit = "😀".repeat(1000);
        CouponCampaignModel campaign = new CouponCampaignModel(" " + name + " ", benefit, true, 5, END_AT);

        assertThat(campaign.getName()).isEqualTo(name);
        assertThat(campaign.getBenefitDescription()).isEqualTo(benefit);
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new CouponCampaignModel(name + "가", benefit, true, 5, END_AT));
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new CouponCampaignModel(name, benefit + "가", true, 5, END_AT));
    }

}
