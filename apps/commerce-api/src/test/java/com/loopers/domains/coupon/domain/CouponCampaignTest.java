package com.loopers.domains.coupon.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class CouponCampaignTest {
    @ParameterizedTest(name = "활성={0}, 총량={1}, 발급 수={2} → 발급 가능={3}")
    @CsvSource({"true, 5, 4, true", "true, 5, 5, false", "true, 5, 0, true", "false, 5, 0, false", "false, 5, 5, false"})
    @DisplayName("캠페인은 활성이고 발급 수가 총량보다 작을 때만 발급 가능하다")
    void canIssueOnlyWhenActiveAndBelowQuota(boolean active, int totalQuota, int issuedCount, boolean expected) {
        CouponCampaign campaign = new CouponCampaign(1, "캠페인", active, totalQuota, issuedCount, 1000);

        assertThat(campaign.canIssue()).isEqualTo(expected);
    }
}
