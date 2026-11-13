package com.loopers.infrastructure.coupon.persistence;

import com.loopers.domain.coupon.repository.CouponIssueRequestStore;
import com.loopers.domain.coupon.vo.CouponIssueRequested;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 공유 테이블을 엔티티 없이 SQL 로 다룬다. 스키마는 commerce-api 엔티티가 만든다 (8주차 D-138, D-172).
 * 순번 ≤ 한도면 WAITING(슬롯 = 순번), 그 밖은 RESERVE 로 적재한다 (8주차 D-121, D-141)
 */
@RequiredArgsConstructor
@Component
public class JdbcCouponIssueRequestStore implements CouponIssueRequestStore {

    private static final String UPSERT = """
        INSERT INTO coupon_issue_request (campaign_id, user_id, sequence, status, slot_no, created_at, updated_at)
        VALUES (:campaignId, :userId, :sequence, :status, :slotNo, :now, :now)
        ON DUPLICATE KEY UPDATE id = id
        """;
    private static final String QUOTAS = "SELECT id, total_quota FROM coupon_campaign WHERE id IN (:ids)";
    private static final String STORED = """
        SELECT campaign_id, user_id, sequence FROM coupon_issue_request
        WHERE campaign_id IN (:campaignIds) AND user_id IN (:userIds)
        """;

    private static final String EXISTS = "SELECT COUNT(*) FROM coupon_issue_request WHERE campaign_id = :campaignId AND user_id = :userId";

    private final NamedParameterJdbcTemplate jdbc;

    @Override
    public boolean exists(long campaignId, long userId) {
        Long count = jdbc.queryForObject(EXISTS, Map.of("campaignId", campaignId, "userId", userId), Long.class);
        return count != null && count > 0;
    }

    @Override
    public List<CouponIssueRequested> loadAll(List<CouponIssueRequested> requests) {
        if (requests.isEmpty()) {
            return List.of();
        }
        Timestamp now = Timestamp.from(Instant.now());
        Map<Long, Integer> quotaOf = quotasOf(requests);
        MapSqlParameterSource[] rows = requests.stream()
            .filter(request -> quotaOf.containsKey(request.campaignId()))
            .map(request -> row(request, quotaOf.get(request.campaignId()), now))
            .toArray(MapSqlParameterSource[]::new);
        jdbc.batchUpdate(UPSERT, rows);
        return mismatched(requests);
    }

    private static MapSqlParameterSource row(CouponIssueRequested request, int totalQuota, Timestamp now) {
        boolean winning = request.sequence() <= totalQuota;
        return new MapSqlParameterSource()
            .addValue("campaignId", request.campaignId())
            .addValue("userId", request.userId())
            .addValue("sequence", request.sequence())
            .addValue("status", winning ? "WAITING" : "RESERVE")
            .addValue("slotNo", winning ? (int) request.sequence() : null)
            .addValue("now", now);
    }

    /** 묶음마다 한 번 읽는다. 묶음 안의 캠페인 수만큼만 읽어 비용이 작다 */
    private Map<Long, Integer> quotasOf(List<CouponIssueRequested> requests) {
        Set<Long> campaignIds = requests.stream().map(CouponIssueRequested::campaignId).collect(Collectors.toSet());
        Map<Long, Integer> quotas = new HashMap<>();
        jdbc.query(QUOTAS, Map.of("ids", campaignIds),
            rs -> {
                quotas.put(rs.getLong("id"), rs.getInt("total_quota"));
            });
        return quotas;
    }

    /** 저장된 순번이 받은 것과 다르면(같은 순번이 다른 사람에게 갔거나 그 반대) 돌려준다 */
    private List<CouponIssueRequested> mismatched(List<CouponIssueRequested> requests) {
        Map<String, Long> stored = new HashMap<>();
        jdbc.query(STORED, Map.of(
                "campaignIds", requests.stream().map(CouponIssueRequested::campaignId).collect(Collectors.toSet()),
                "userIds", requests.stream().map(CouponIssueRequested::userId).collect(Collectors.toSet())),
            rs -> {
                stored.put(rs.getLong("campaign_id") + ":" + rs.getLong("user_id"), rs.getLong("sequence"));
            });
        List<CouponIssueRequested> mismatched = new ArrayList<>();
        for (CouponIssueRequested request : requests) {
            Long sequence = stored.get(request.campaignId() + ":" + request.userId());
            if (sequence == null || sequence != request.sequence()) {
                mismatched.add(request);
            }
        }
        return mismatched;
    }
}
