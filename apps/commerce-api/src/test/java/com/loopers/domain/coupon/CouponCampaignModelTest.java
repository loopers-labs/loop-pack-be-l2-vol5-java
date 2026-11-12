package com.loopers.domain.coupon;

import com.loopers.support.error.CoreException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CouponCampaignModelTest {

    private static final ZonedDateTime START = ZonedDateTime.now();
    private static final ZonedDateTime END = START.plusDays(1);

    @DisplayName("정상 파라미터로 캠페인을 생성할 수 있다.")
    @Test
    void create_success() {
        CouponCampaignModel campaign = new CouponCampaignModel("여름 쿠폰", 100, START, END);
        assertThat(campaign.getName()).isEqualTo("여름 쿠폰");
        assertThat(campaign.getTotalQuota()).isEqualTo(100);
        assertThat(campaign.getIssuedCount()).isZero();
        assertThat(campaign.isQuotaExhausted()).isFalse();
    }

    @DisplayName("이름이 비어 있으면 생성에 실패한다.")
    @Test
    void create_blankName_fails() {
        assertThatThrownBy(() -> new CouponCampaignModel("", 100, START, END))
            .isInstanceOf(CoreException.class);
    }

    @DisplayName("발급 수량이 0이하면 생성에 실패한다.")
    @Test
    void create_zeroQuota_fails() {
        assertThatThrownBy(() -> new CouponCampaignModel("쿠폰", 0, START, END))
            .isInstanceOf(CoreException.class);
    }

    @DisplayName("종료 시각이 시작 시각보다 이전이면 생성에 실패한다.")
    @Test
    void create_invalidDateRange_fails() {
        assertThatThrownBy(() -> new CouponCampaignModel("쿠폰", 10, END, START))
            .isInstanceOf(CoreException.class);
    }

    @DisplayName("isQuotaExhausted는 issuedCount >= totalQuota일 때 true를 반환한다.")
    @Test
    void isQuotaExhausted_whenFull() {
        CouponCampaignModel campaign = new CouponCampaignModel("쿠폰", 1, START, END);
        assertThat(campaign.isQuotaExhausted()).isFalse();
    }
}
