package com.loopers.coupon.domain;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CouponCampaignTest {

    @DisplayName("[INV-COUPONCAMPAIGN-52] 캠페인의 현재 발급 수는 총 발급 한도 이하다.")
    @Nested
    class IssuedCountWithinQuota {

        @DisplayName("[경계값 분석] 한도에 도달한 캠페인의 추가 발급은 거절되고 발급 수는 유지된다.")
        @Test
        void rejectsIssueAtTheQuotaWithoutIncreasingCount() {
            // arrange
            CouponCampaign campaign = new CouponCampaign("출시 캠페인", "5,000원 할인권", true, 1);
            campaign.issue();

            // act
            CoreException result = assertThrows(CoreException.class, campaign::issue);

            // assert
            assertThat(result.getErrorCode()).isEqualTo(ErrorCode.COUPON_CAMPAIGN_SOLD_OUT);
            assertThat(campaign.getIssuedCount()).isEqualTo(1);
        }
    }
}
