package com.loopers.support;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;

/** streamer 에 엔티티가 없는 공유 쿠폰 테이블을 테스트에서 채우고 비운다 (8주차 D-172) */
@Component
public class CouponTables {

    private final JdbcTemplate jdbc;

    public CouponTables(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insertOpenCampaign(int totalQuota) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
            INSERT INTO coupon_campaign (created_at, updated_at, name, status, total_quota, issued_count, discount_type, discount_value, description)
            VALUES (?, ?, '행사', 'OPEN', ?, 0, 'FIXED_AMOUNT', 5000, '5,000원 할인권')
            """, now, now, totalQuota);
        return jdbc.queryForObject("SELECT MAX(id) FROM coupon_campaign", Long.class);
    }

    public void truncate() {
        jdbc.execute("TRUNCATE TABLE coupon_issue_request");
        jdbc.execute("TRUNCATE TABLE coupon");
        jdbc.execute("TRUNCATE TABLE coupon_campaign");
    }
}
