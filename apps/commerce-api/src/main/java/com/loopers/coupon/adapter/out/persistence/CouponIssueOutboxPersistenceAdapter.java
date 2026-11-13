package com.loopers.coupon.adapter.out.persistence;

import com.loopers.coupon.application.port.out.CouponIssueOutboxPort;
import com.loopers.coupon.domain.CouponIssueOutbox;
import com.loopers.coupon.domain.CouponIssueOutboxStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@RequiredArgsConstructor
@Component
public class CouponIssueOutboxPersistenceAdapter implements CouponIssueOutboxPort {

    private static final EnumSet<CouponIssueOutboxStatus> DUE_STATUSES =
        EnumSet.of(CouponIssueOutboxStatus.PENDING, CouponIssueOutboxStatus.SENDING);

    /**
     * 이미 있으면 UNIQUE(campaign_id, user_id) 위반 → 중복으로 보고 false. INSERT IGNORE는 다른 오류(칸 길이 등)까지 경고로 삼키고,
     * ON DUPLICATE KEY UPDATE는 드라이버 기본값(found rows)에서 새로 넣었는지 구분되지 않아 쓰지 않는다.
     * 한 문장의 실패라 바깥 트랜잭션은 그대로 쓸 수 있다(MySQL은 문장 단위로 되돌린다).
     */
    private static final String INSERT = """
        INSERT INTO coupon_issue_outbox
            (campaign_id, user_id, message_key, payload, status, attempt_count, next_attempt_at, created_at, updated_at)
        VALUES (?, ?, ?, ?, 'PENDING', 0, ?, ?, ?)
        """;

    private final CouponIssueOutboxJpaRepository jpaRepository;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public boolean appendIfAbsent(Long campaignId, Long userId, ZonedDateTime now) {
        // Hibernate는 시각을 UTC로 저장한다(jpa.yml NORMALIZE_UTC · jdbc.time_zone UTC). 같은 기준으로 UTC LocalDateTime을 넣는다 —
        // Timestamp를 넘기면 드라이버가 JVM 시간대(KST)로 바꿔 9시간 미래가 되어 relay가 고르지 않는다.
        LocalDateTime at = now.withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        String payload = "{\"campaignId\":" + campaignId + ",\"userId\":" + userId + "}";
        try {
            jdbcTemplate.update(INSERT, campaignId, userId, String.valueOf(userId), payload, at, at, at);
            return true;
        } catch (DuplicateKeyException alreadyRecorded) {
            return false;
        }
    }

    @Override
    public CouponIssueOutbox save(CouponIssueOutbox outbox) {
        return jpaRepository.save(outbox);
    }

    @Override
    public Optional<CouponIssueOutbox> findById(Long id) {
        return jpaRepository.findById(id);
    }

    @Override
    public List<CouponIssueOutbox> findDue(ZonedDateTime now, int limit) {
        return jpaRepository.findDue(DUE_STATUSES, now, PageRequest.of(0, limit));
    }

    @Override
    public Set<Long> findUserIds(Long campaignId) {
        return new HashSet<>(jpaRepository.findUserIds(campaignId));
    }

    @Override
    public Set<Long> findRecordedUserIds(Long campaignId, Collection<Long> userIds) {
        if (userIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jpaRepository.findRecordedUserIds(campaignId, userIds));
    }
}
