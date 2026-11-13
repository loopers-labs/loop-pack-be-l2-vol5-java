package com.loopers.couponissue.support;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * streamer 테스트용 캠페인·쿠폰 행. 테이블의 주인은 commerce-api라서, streamer가 보는 칸만 넣는다.
 */
public final class CouponIssueFixtures {

    private CouponIssueFixtures() {}

    /** 당첨자 수 totalQuantity, 쿠폰 두 장(정해진 기간 · 발급 후 7일)인 캠페인 */
    public static void campaignWithTwoCoupons(JdbcTemplate jdbc, long campaignId, int totalQuantity) {
        jdbc.update("INSERT INTO coupon_campaign (id, total_quantity, issued_count) VALUES (?, ?, 0)", campaignId, totalQuantity);
        jdbc.update("INSERT INTO coupon (id, campaign_id, validity_type, valid_until, valid_days) VALUES (?, ?, 'FIXED_PERIOD', '2026-11-30 00:00:00', NULL)",
            campaignId * 10 + 1, campaignId);
        jdbc.update("INSERT INTO coupon (id, campaign_id, validity_type, valid_until, valid_days) VALUES (?, ?, 'DAYS_FROM_ISSUE', NULL, 7)",
            campaignId * 10 + 2, campaignId);
    }

    public static int issuedCount(JdbcTemplate jdbc, long campaignId) {
        return jdbc.queryForObject("SELECT issued_count FROM coupon_campaign WHERE id = ?", Integer.class, campaignId);
    }

    public static long userCoupons(JdbcTemplate jdbc, long campaignId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM user_coupon WHERE campaign_id = ?", Long.class, campaignId);
    }

    public static long userCoupons(JdbcTemplate jdbc, long campaignId, long userId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM user_coupon WHERE campaign_id = ? AND user_id = ?", Long.class, campaignId, userId);
    }
}
