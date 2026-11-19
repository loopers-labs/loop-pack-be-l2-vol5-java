package com.loopers.domains.coupon.out;

import com.loopers.domains.coupon.app.port.CouponIssueLookup;
import com.loopers.domains.coupon.app.port.CouponRepository;
import com.loopers.domains.coupon.app.port.DuplicateIssueException;
import com.loopers.domains.coupon.domain.CouponCampaign;
import com.loopers.domains.coupon.domain.IssuedCoupon;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

// increaseIssued·insert는 app의 발급 트랜잭션 안에서 불린다(스스로 트랜잭션을 열지 않는다).
// findForIssue는 트랜잭션 없이 읽는다. 유일 제약 충돌 뒤 재조회가 실패한 트랜잭션과 섞이지 않는다.
@Repository
public class CouponPersistenceAdapter implements CouponRepository {
    private final CouponCampaignJpaRepository couponCampaignJpaRepository;
    private final IssuedCouponJpaRepository issuedCouponJpaRepository;

    public CouponPersistenceAdapter(CouponCampaignJpaRepository couponCampaignJpaRepository,
        IssuedCouponJpaRepository issuedCouponJpaRepository) {
        this.couponCampaignJpaRepository = couponCampaignJpaRepository;
        this.issuedCouponJpaRepository = issuedCouponJpaRepository;
    }

    @Override
    public Optional<CouponIssueLookup> findForIssue(long campaignId, long userId) {
        List<Object[]> rows = couponCampaignJpaRepository.findWithUserCoupon(campaignId, userId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Object[] row = rows.getFirst();
        CouponCampaignJpaEntity campaign = (CouponCampaignJpaEntity) row[0];
        IssuedCouponJpaEntity coupon = (IssuedCouponJpaEntity) row[1];
        Optional<IssuedCoupon> owned = Optional.ofNullable(coupon).map(CouponPersistenceAdapter::toDomain);
        return Optional.of(new CouponIssueLookup(toDomain(campaign), owned));
    }

    @Override
    public int increaseIssued(long campaignId) {
        return couponCampaignJpaRepository.increaseIssued(campaignId);
    }

    // 혜택은 ①에서 읽은 값을 쓰지 않고 여기서 다시 읽어 복사한다(D131). 바로 flush해 유일 제약 충돌이 commit이 아니라 이 호출에서 드러나게 한다.
    @Override
    public IssuedCoupon insert(long campaignId, long userId, Instant issuedAt) {
        CouponCampaignJpaEntity campaign = couponCampaignJpaRepository.findById(campaignId)
            .orElseThrow(() -> new IllegalStateException("발급 중인 캠페인이 없습니다. campaignId=" + campaignId));
        try {
            return toDomain(issuedCouponJpaRepository.saveAndFlush(
                new IssuedCouponJpaEntity(campaignId, userId, campaign.discountAmount(), issuedAt)));
        } catch (DataIntegrityViolationException e) {
            if (isCampaignUserDuplicate(e)) {
                throw new DuplicateIssueException("이미 발급된 쿠폰입니다. campaignId=" + campaignId + ", userId=" + userId, e);
            }
            throw e;
        }
    }

    @Override
    public List<IssuedCoupon> findByUser(long userId) {
        return issuedCouponJpaRepository.findByUserNewestFirst(userId).stream().map(CouponPersistenceAdapter::toDomain).toList();
    }

    // 다른 제약 위반(NOT NULL 등)을 중복 발급으로 바꾸지 않도록 원인 사슬에서 이 유일 제약 이름을 찾는다.
    private static boolean isCampaignUserDuplicate(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause.getMessage() != null && cause.getMessage().contains(IssuedCouponJpaEntity.UNIQUE_CAMPAIGN_USER)) {
                return true;
            }
        }
        return false;
    }

    private static CouponCampaign toDomain(CouponCampaignJpaEntity entity) {
        return new CouponCampaign(entity.id(), entity.name(), entity.active(), entity.totalQuota(), entity.issuedCount(),
            entity.discountAmount());
    }

    private static IssuedCoupon toDomain(IssuedCouponJpaEntity entity) {
        return new IssuedCoupon(entity.id(), entity.campaignId(), entity.userId(), entity.discountAmount(), entity.issuedAt());
    }
}
