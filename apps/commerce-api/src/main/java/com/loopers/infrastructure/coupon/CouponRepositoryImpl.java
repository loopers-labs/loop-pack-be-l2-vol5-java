package com.loopers.infrastructure.coupon;

import com.loopers.domain.coupon.Coupon;
import com.loopers.domain.coupon.CouponRepository;
import com.loopers.domain.coupon.DuplicateCouponIssueException;
import com.loopers.infrastructure.support.MySqlConstraintErrors;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class CouponRepositoryImpl implements CouponRepository {

    private final CouponJpaRepository jpaRepository;
    private final CouponCampaignJpaRepository campaigns;

    @Override
    public List<Coupon> saveStock(List<Coupon> stock) {
        if (stock.stream().anyMatch(coupon -> coupon.getId() != null || coupon.isIssued())) {
            throw new IllegalArgumentException("새 미발급 쿠폰만 준비 단계에서 저장할 수 있습니다.");
        }
        List<CouponJpaEntity> entities = stock.stream()
            .map(coupon -> CouponJpaEntity.create(campaigns.getReferenceById(coupon.getCampaignId())))
            .toList();
        return jpaRepository.saveAllAndFlush(entities).stream().map(CouponJpaMapper::toDomain).toList();
    }

    @Override
    public Optional<Coupon> findById(Long couponId) {
        return jpaRepository.findByIdAndDeletedAtIsNull(couponId).map(CouponJpaMapper::toDomain);
    }

    @Override
    public Optional<Long> findFirstUnissuedId(Long campaignId) {
        return jpaRepository.findFirstUnissuedId(campaignId);
    }

    @Override
    public Optional<Coupon> findIssuedByCampaignIdAndUserId(Long campaignId, Long userId) {
        return jpaRepository.findByCampaignIdAndUserIdAndIssuedAtIsNotNullAndDeletedAtIsNull(campaignId, userId)
            .map(CouponJpaMapper::toDomain);
    }

    @Override
    public List<Coupon> findAllIssuedByUserId(Long userId) {
        return jpaRepository.findAllByUserIdAndIssuedAtIsNotNullAndDeletedAtIsNullOrderByIssuedAtDescIdDesc(userId)
            .stream().map(CouponJpaMapper::toDomain).toList();
    }

    @Override
    public long countByCampaignId(Long campaignId) {
        return jpaRepository.countByCampaignIdAndDeletedAtIsNull(campaignId);
    }

    @Override
    public long countIssuedByCampaignId(Long campaignId) {
        return jpaRepository.countByCampaignIdAndUserIdIsNotNullAndIssuedAtIsNotNullAndDeletedAtIsNull(campaignId);
    }

    @Override
    public int assignIfUnissued(Long couponId, Long campaignId, Long userId, Instant issueNow) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(issueNow, "issueNow");
        try {
            return jpaRepository.assignIfUnissued(couponId, campaignId, userId, issueNow);
        } catch (DataIntegrityViolationException exception) {
            if (MySqlConstraintErrors.isDuplicateKey(exception, "uk_coupon_campaign_user")) {
                throw new DuplicateCouponIssueException(exception);
            }
            throw exception;
        }
    }

    @Override
    public int resetSeedAssignments(Long campaignId, Instant resetNow) {
        return jpaRepository.resetSeedAssignments(campaignId, Objects.requireNonNull(resetNow, "resetNow"));
    }
}
