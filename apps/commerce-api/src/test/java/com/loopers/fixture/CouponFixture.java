package com.loopers.fixture;

import com.loopers.domain.coupon.CouponBenefitType;
import com.loopers.domain.coupon.CouponCampaignModel;
import com.loopers.domain.point.PointModel;
import com.loopers.domain.user.UserModel;
import com.loopers.infrastructure.coupon.CouponCampaignJpaRepository;
import com.loopers.infrastructure.point.PointJpaRepository;
import com.loopers.infrastructure.user.UserJpaRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * 쿠폰 자동 테스트의 조건별 준비 코드. 생성 ID 를 반환하며 로컬 기본 데이터에 의존하지 않는다.
 *
 * <p>저장 메서드는 기본 전파(REQUIRED)의 TransactionTemplate·JdbcTemplate 을 쓰므로 상위 TX 가 있으면 거기에 참여한다.
 * worker 시작 전 준비 commit 을 보장하려면 상위 TX 없이 호출해야 한다.
 *
 * <p>비활성·이름/혜택 변경·연결 손상은 제품에 수정 기능을 추가하지 않고 test 소스의 SQL 로 준비한다.
 */
@Component
public class CouponFixture {

    private final UserJpaRepository userJpaRepository;
    private final PointJpaRepository pointJpaRepository;
    private final CouponCampaignJpaRepository campaignJpaRepository;
    private final TransactionTemplate transactionTemplate;
    private final JdbcTemplate jdbcTemplate;

    public CouponFixture(UserJpaRepository userJpaRepository, PointJpaRepository pointJpaRepository,
                         CouponCampaignJpaRepository campaignJpaRepository, TransactionTemplate transactionTemplate,
                         JdbcTemplate jdbcTemplate) {
        this.userJpaRepository = userJpaRepository;
        this.pointJpaRepository = pointJpaRepository;
        this.campaignJpaRepository = campaignJpaRepository;
        this.transactionTemplate = transactionTemplate;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 사용자와 잔액 0 Point 를 count 명 만든다. */
    public List<Long> users(int count) {
        return transactionTemplate.execute(status -> {
            List<Long> ids = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                UserModel user = userJpaRepository.save(UserModel.create());
                pointJpaRepository.save(PointModel.of(user.getId()));
                ids.add(user.getId());
            }
            return List.copyOf(ids);
        });
    }

    public Long user() {
        return users(1).get(0);
    }

    /** 승인된 모델 행동으로 ACTIVE·issuedCount 0 캠페인을 만들고 ID 를 반환한다. */
    public Long activeCampaign(String name, long totalQuota, CouponBenefitType type, long value) {
        return transactionTemplate.execute(status ->
            campaignJpaRepository.save(CouponCampaignModel.create(name, totalQuota, type, value)).getId());
    }

    public Long activeCampaign(long totalQuota) {
        return activeCampaign("테스트 캠페인", totalQuota, CouponBenefitType.FIXED_AMOUNT, 5_000L);
    }

    /** 생성 후 test SQL 로 INACTIVE 로 바꾼다. */
    public Long inactiveCampaign(long totalQuota) {
        Long id = activeCampaign(totalQuota);
        requireOneRow(jdbcTemplate.update("UPDATE coupon_campaign SET status = 'INACTIVE' WHERE id = ?", id));
        return id;
    }

    /** 생성 후 test SQL 로 발급 수를 총량까지 채운다. 실제 쿠폰 행은 만들지 않는다. */
    public Long soldOutCampaign(long totalQuota) {
        Long id = activeCampaign(totalQuota);
        requireOneRow(jdbcTemplate.update("UPDATE coupon_campaign SET issued_count = total_quota WHERE id = ?", id));
        return id;
    }

    public void rename(Long campaignId, String name) {
        requireOneRow(jdbcTemplate.update("UPDATE coupon_campaign SET name = ? WHERE id = ?", name, campaignId));
    }

    public void changeBenefit(Long campaignId, CouponBenefitType type, long value) {
        requireOneRow(jdbcTemplate.update("UPDATE coupon_campaign SET benefit_type = ?, benefit_value = ? WHERE id = ?",
            type.name(), value, campaignId));
    }

    /** 연결 손상 재현: 쿠폰은 남기고 캠페인 행만 지운다. 제품의 캠페인 삭제 기능이 아니다. */
    public void breakCampaignLink(Long campaignId) {
        requireOneRow(jdbcTemplate.update("DELETE FROM coupon_campaign WHERE id = ?", campaignId));
    }

    /** 같은 발급 시각의 보조 정렬 검증용. DB 저장 정밀도의 고정 시각을 준다. */
    public void setCouponCreatedAt(Long couponId, String utcDateTime6) {
        requireOneRow(jdbcTemplate.update("UPDATE coupon SET created_at = ? WHERE id = ?", utcDateTime6, couponId));
    }

    public long issuedCount(Long campaignId) {
        return jdbcTemplate.queryForObject("SELECT issued_count FROM coupon_campaign WHERE id = ?", Long.class, campaignId);
    }

    public long couponCount(Long campaignId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM coupon WHERE campaign_id = ?", Long.class, campaignId);
    }

    public long couponCount(Long campaignId, Long userId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM coupon WHERE campaign_id = ? AND user_id = ?",
            Long.class, campaignId, userId);
    }

    /** DB 에 저장된 created_at 원문(UTC, 소수 6자리). */
    public String storedCreatedAt(Long couponId) {
        return jdbcTemplate.queryForObject(
            "SELECT DATE_FORMAT(created_at, '%Y-%m-%dT%H:%i:%s.%f') FROM coupon WHERE id = ?", String.class, couponId);
    }

    private static void requireOneRow(int updated) {
        if (updated != 1) {
            throw new IllegalStateException("준비 SQL 이 한 행을 바꾸지 않았습니다. [updated = " + updated + "]");
        }
    }
}
