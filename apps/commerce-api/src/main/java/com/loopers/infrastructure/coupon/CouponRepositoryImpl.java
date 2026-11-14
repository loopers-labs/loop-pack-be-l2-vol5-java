package com.loopers.infrastructure.coupon;

import com.loopers.domain.coupon.Coupon;
import com.loopers.domain.coupon.CouponRepository;
import com.loopers.domain.coupon.DuplicateCouponIssueException;
import com.loopers.infrastructure.support.MySqlConstraintErrors;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class CouponRepositoryImpl implements CouponRepository {

    private final CouponJpaRepository jpaRepository;
    private final CouponCampaignJpaRepository campaigns;

    @Override
    public Optional<Coupon> findById(Long couponId) {
        return jpaRepository.findByIdAndDeletedAtIsNull(couponId).map(CouponJpaMapper::toDomain);
    }

    @Override
    public Coupon insert(Coupon issued) {
        if (issued.getId() != null) {
            throw new IllegalArgumentException("새로 발급한 쿠폰만 저장할 수 있습니다.");
        }
        CouponJpaEntity entity = CouponJpaEntity.create(campaigns.getReferenceById(issued.getCampaignId()),
            issued.getUserId(), issued.getIssuedAt());
        try {
            // 같은 트랜잭션 안에서 유일 제약 충돌을 확인하도록 바로 반영한다.
            return CouponJpaMapper.toDomain(jpaRepository.saveAndFlush(entity));
        } catch (DataIntegrityViolationException exception) {
            if (MySqlConstraintErrors.isDuplicateKey(exception, "uk_coupon_campaign_user")) {
                throw new DuplicateCouponIssueException(exception);
            }
            throw exception;
        }
    }

    @Override
    public int deleteByCampaignId(Long campaignId) {
        return jpaRepository.deleteByCampaignId(campaignId);
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
    public long countIssuedByCampaignId(Long campaignId) {
        return jpaRepository.countByCampaignIdAndUserIdIsNotNullAndIssuedAtIsNotNullAndDeletedAtIsNull(campaignId);
    }
}
