package com.loopers.infrastructure.persistence.coupon.repository;

import com.loopers.domain.coupon.exception.DuplicateCouponIssueException;
import com.loopers.domain.coupon.model.Coupon;
import com.loopers.domain.coupon.repository.CouponRepository;
import com.loopers.infrastructure.persistence.coupon.entity.CouponEntityMapper;
import com.loopers.infrastructure.persistence.coupon.jpa.CouponJpaRepository;
import com.loopers.infrastructure.persistence.coupon.support.CouponSqlFailures;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class CouponRepositoryImpl implements CouponRepository {
    private final CouponJpaRepository jpaRepository;
    private final CouponEntityMapper mapper;

    @Override
    public Coupon save(Coupon coupon) {
        if (coupon.getId() != null) {
            throw new IllegalArgumentException("발급 완료 쿠폰은 수정하거나 다시 저장하지 않습니다.");
        }
        try {
            return mapper.toDomain(jpaRepository.saveAndFlush(mapper.toNewEntity(coupon)));
        } catch (DataIntegrityViolationException failure) {
            if (CouponSqlFailures.isUserDuplicate(failure)) {
                throw new DuplicateCouponIssueException(failure);
            }
            throw failure;
        }
    }

    @Override
    public Optional<Coupon> findByCampaignIdAndUserId(long campaignId, long userId) {
        return jpaRepository.findByCampaignIdAndUserId(campaignId, userId).map(mapper::toDomain);
    }
}
