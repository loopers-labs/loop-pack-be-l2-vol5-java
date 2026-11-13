package com.loopers.infrastructure.coupon.persistence;

import com.loopers.domain.coupon.repository.CouponIssuanceStore;
import com.loopers.domain.coupon.vo.IssueEntry;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 발급 TX: 쿠폰 insert(슬롯) + 접수 기록 ISSUED. 캠페인 행은 갱신하지 않는다 (8주차 D-141).
 * 접수 기록이 아직 적재되지 않았으면 이 TX 가 만든다(upsert) (8주차 D-132)
 */
@RequiredArgsConstructor
@Component
public class JdbcCouponIssuanceStore implements CouponIssuanceStore {

    private static final String OPEN_CAMPAIGNS = "SELECT id FROM coupon_campaign WHERE status = 'OPEN' ORDER BY id";
    private static final String EXISTING_COUPON = "SELECT id FROM coupon WHERE campaign_id = :campaignId AND user_id = :userId";
    private static final String CAMPAIGN = """
        SELECT status, total_quota, discount_type, discount_value, description FROM coupon_campaign WHERE id = :campaignId
        """;
    private static final String INSERT_COUPON = """
        INSERT INTO coupon (campaign_id, user_id, slot_no, discount_type, discount_value, description, issued_at, created_at, updated_at)
        VALUES (:campaignId, :userId, :slotNo, :discountType, :discountValue, :description, :now, :now, :now)
        """;
    private static final String MARK_ISSUED = """
        INSERT INTO coupon_issue_request (campaign_id, user_id, sequence, status, slot_no, coupon_id, created_at, updated_at)
        VALUES (:campaignId, :userId, :sequence, 'ISSUED', :slotNo, :couponId, :now, :now)
        ON DUPLICATE KEY UPDATE status = 'ISSUED', slot_no = VALUES(slot_no), coupon_id = VALUES(coupon_id), updated_at = VALUES(updated_at)
        """;

    private final NamedParameterJdbcTemplate jdbc;

    @Override
    public List<Long> findOpenCampaignIds() {
        return jdbc.getJdbcTemplate().queryForList(OPEN_CAMPAIGNS, Long.class);
    }

    @Override
    public long issue(IssueEntry entry, Instant now) {
        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("campaignId", entry.campaignId())
            .addValue("userId", entry.userId())
            .addValue("sequence", entry.sequence())
            .addValue("slotNo", entry.slotNo())
            .addValue("now", Timestamp.from(now));
        List<Long> existing = jdbc.queryForList(EXISTING_COUPON, params, Long.class);
        long couponId = existing.isEmpty() ? insertCoupon(entry, params) : existing.get(0);
        jdbc.update(MARK_ISSUED, params.addValue("couponId", couponId));
        return couponId;
    }

    /** 캠페인이 열려 있고 슬롯이 1 ~ 한도일 때만 넣는다. 혜택은 캠페인 값을 복사한다 (8주차 D-141, D-150) */
    private long insertCoupon(IssueEntry entry, MapSqlParameterSource params) {
        Map<String, Object> campaign = jdbc.queryForList(CAMPAIGN, params).stream().findFirst()
            .orElseThrow(() -> new IllegalStateException("캠페인이 없음. campaignId=" + entry.campaignId()));
        if (!"OPEN".equals(campaign.get("status"))) {
            throw new IllegalStateException("열린 캠페인이 아님. campaignId=" + entry.campaignId() + " status=" + campaign.get("status"));
        }
        int totalQuota = ((Number) campaign.get("total_quota")).intValue();
        if (entry.slotNo() < 1 || entry.slotNo() > totalQuota) {
            throw new IllegalStateException("한도 밖 슬롯. campaignId=" + entry.campaignId() + " slotNo=" + entry.slotNo());
        }
        params.addValue("discountType", campaign.get("discount_type"))
            .addValue("discountValue", campaign.get("discount_value"))
            .addValue("description", campaign.get("description"));
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(INSERT_COUPON, params, keyHolder, new String[] {"id"});
        return keyHolder.getKey().longValue();
    }
}
