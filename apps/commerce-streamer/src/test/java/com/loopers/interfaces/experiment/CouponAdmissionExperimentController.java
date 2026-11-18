package com.loopers.interfaces.experiment;

import com.loopers.application.coupon.facade.CouponAdmission;
import com.loopers.application.coupon.facade.CouponIssueSteps;
import com.loopers.config.CouponRedisConfig;
import com.loopers.domain.coupon.vo.IssueEntry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 세마포어 실험용 진입점. 과제 흐름 그대로: 기존 쿠폰 확인 → 자리 획득 → 실제 DB 발급 TX → 자기 token 반환, 자리가 없으면 429 (8주차 D-157, D-158).
 * 운영 코드가 아니라 테스트 소스에만 둔다
 */
@RestController
public class CouponAdmissionExperimentController {

    private final CouponAdmission admission;
    private final CouponIssueSteps couponIssueSteps;
    private final AdmissionBarrier barrier;
    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;

    public CouponAdmissionExperimentController(
        CouponAdmission admission,
        CouponIssueSteps couponIssueSteps,
        AdmissionBarrier barrier,
        JdbcTemplate jdbc,
        @Qualifier(CouponRedisConfig.MASTER_TEMPLATE) StringRedisTemplate redis
    ) {
        this.admission = admission;
        this.couponIssueSteps = couponIssueSteps;
        this.barrier = barrier;
        this.jdbc = jdbc;
        this.redis = redis;
    }

    @PostMapping("/experiment/coupon-campaigns/{campaignId}/issues")
    public ResponseEntity<Map<String, Object>> issue(@PathVariable long campaignId, @RequestHeader("X-USER-ID") long userId)
        throws InterruptedException {
        List<Long> existing = jdbc.queryForList("SELECT id FROM coupon WHERE campaign_id = ? AND user_id = ?", Long.class, campaignId, userId);
        if (!existing.isEmpty()) {
            return ResponseEntity.ok(Map.of("couponId", existing.get(0)));
        }
        CouponAdmission.Result result = admission.tryAcquire(campaignId);
        if (result instanceof CouponAdmission.Result.Unavailable) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("status", "UNAVAILABLE"));
        }
        if (!(result instanceof CouponAdmission.Result.Acquired permit)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of("status", "NO_SLOT"));
        }
        try {
            barrier.arrive();
            long sequence = redis.opsForValue().increment("experiment:{" + campaignId + "}:sequence");
            int totalQuota = jdbc.queryForObject("SELECT total_quota FROM coupon_campaign WHERE id = ?", Integer.class, campaignId);
            if (sequence > totalQuota) {
                return ResponseEntity.ok(Map.of("status", "SOLD_OUT"));
            }
            long couponId = couponIssueSteps.issue(new IssueEntry(campaignId, "experiment", userId, sequence, (int) sequence, "experiment", 0, 0, 1));
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("couponId", couponId));
        } finally {
            // 발급 TX 가 commit · rollback 으로 끝난 뒤 반환한다 (D-113)
            admission.release(permit);
        }
    }
}
