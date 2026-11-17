package com.loopers.infrastructure.query.coupon;

import static org.assertj.core.api.Assertions.assertThat;

import com.loopers.application.coupon.command.CouponCampaignCommand;
import com.loopers.application.coupon.command.CouponIssueCommand;
import com.loopers.application.coupon.query.CouponQueryDao;
import com.loopers.application.coupon.query.CouponView;
import com.loopers.application.coupon.result.CouponIssueResult;
import com.loopers.application.coupon.service.IssueCouponService;
import com.loopers.application.coupon.service.PrepareCouponCampaignService;
import com.loopers.support.test.IntegrationTest;
import com.loopers.utils.DatabaseCleanUp;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@IntegrationTest
class CouponQueryDaoIntegrationTest {
    @Autowired private CouponQueryDao queryDao;
    @Autowired private PrepareCouponCampaignService preparation;
    @Autowired private IssueCouponService issuance;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DatabaseCleanUp cleanUp;

    @AfterEach
    void tearDown() {
        cleanUp.truncateAllTables();
    }

    @Test
    @DisplayName("본인 쿠폰만 발급 시각과 ID 내림차순으로 모두 조회하고 캠페인 상태와 무관하게 저장된 혜택을 반환한다")
    void findsOwnedCouponsInStableOrderWithStoredBenefits() {
        CouponIssueResult first = issue(campaign(), 11L);
        CouponIssueResult second = issue(campaign(), 11L);
        CouponIssueResult older = issue(campaign(), 11L);
        issue(campaign(), 22L);
        // 동일 시각의 ID 보조 정렬과 시각 우선 정렬을 결정적으로 검증한다.
        jdbc.update("UPDATE coupons SET issued_at = '2026-01-02 00:00:00' WHERE id IN (?, ?)",
            first.couponId(), second.couponId());
        jdbc.update("UPDATE coupons SET issued_at = '2026-01-01 00:00:00' WHERE id = ?", older.couponId());
        jdbc.update("UPDATE coupon_campaigns SET active = false, starts_at = '2025-01-01 00:00:00', "
            + "ends_at = '2025-01-02 00:00:00', benefit_description = '변경된 혜택', discount_amount = 9999");

        List<CouponView> result = queryDao.findCoupons(11L);

        assertThat(result).extracting(CouponView::couponId)
            .containsExactly(second.couponId(), first.couponId(), older.couponId());
        assertThat(result).allSatisfy(coupon -> {
            assertThat(coupon.benefitDescription()).isEqualTo("발급 당시 혜택");
            assertThat(coupon.discountAmount()).isEqualTo(1000L);
        });
        assertThat(result.getFirst().campaignId()).isEqualTo(second.campaignId());
        assertThat(result.getFirst().issuedAt()).isEqualTo(Instant.parse("2026-01-02T00:00:00Z"));
    }

    @Test
    @DisplayName("기존 발급 결과는 사용자와 캠페인 모두 일치할 때만 반환하고 마감 뒤에도 같은 결과를 유지한다")
    void findsExistingResultOnlyForMatchingOwnerAndCampaign() {
        long campaignId = campaign();
        CouponIssueResult issued = issue(campaignId, 11L);
        long otherCampaignId = campaign();
        issue(otherCampaignId, 22L);
        jdbc.update("UPDATE coupon_campaigns SET active = false, starts_at = '2025-01-01 00:00:00', "
            + "ends_at = '2025-01-02 00:00:00' WHERE id = ?", campaignId);

        assertThat(queryDao.findIssuedCoupon(11L, campaignId)).contains(new CouponView(
            issued.couponId(), campaignId, issued.issuedAt(), issued.benefitDescription(), issued.discountAmount()));
        assertThat(queryDao.findIssuedCoupon(22L, campaignId)).isEmpty();
        assertThat(queryDao.findIssuedCoupon(11L, otherCampaignId)).isEmpty();
        assertThat(queryDao.findIssuedCoupon(11L, Long.MAX_VALUE)).isEmpty();
    }

    @Test
    @DisplayName("본인 발급 내역이 없으면 타인 내역이 있어도 빈 목록을 반환한다")
    void returnsEmptyListWhenUserHasNoCoupons() {
        issue(campaign(), 22L);

        assertThat(queryDao.findCoupons(11L)).isEmpty();
    }

    private long campaign() {
        Instant now = Instant.now();
        long campaignId = preparation.execute(new CouponCampaignCommand.Create("조회 검증", 1,
            "발급 당시 혜택", 1000L, now.minusSeconds(3600), now.plusSeconds(3600))).campaignId();
        preparation.execute(new CouponCampaignCommand.Activation(campaignId, true));
        return campaignId;
    }

    private CouponIssueResult issue(long campaignId, long userId) {
        return issuance.execute(new CouponIssueCommand(campaignId, userId));
    }
}
