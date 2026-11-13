package com.loopers.infrastructure.coupon;

import com.loopers.domain.coupon.Coupon;
import com.loopers.domain.coupon.CouponRepository;
import com.loopers.support.error.DomainError;
import com.loopers.support.error.DomainException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class CouponRepositoryImpl implements CouponRepository {

    private final CouponJpaRepository couponJpaRepository;

    @Override
    public Coupon insert(Coupon coupon) {
        try {
            return couponJpaRepository.saveAndFlush(CouponEntity.from(coupon)).toDomain();
        } catch (DataIntegrityViolationException e) {
            throw duplicateOrRethrow(e);
        }
    }

    @Override
    public List<Coupon> insertAll(List<Coupon> coupons) {
        try {
            return couponJpaRepository.saveAllAndFlush(coupons.stream().map(CouponEntity::from).toList()).stream()
                .map(CouponEntity::toDomain)
                .toList();
        } catch (DataIntegrityViolationException e) {
            throw duplicateOrRethrow(e);
        }
    }

    private static RuntimeException duplicateOrRethrow(DataIntegrityViolationException e) {
        if (DuplicateKey.on(e, CouponEntity.UK_CAMPAIGN_USER)) {
            return new DomainException(DomainError.COUPON_ALREADY_ISSUED);
        }
        return e;
    }

    @Override
    public Optional<Coupon> find(Long campaignId, Long userId) {
        return couponJpaRepository.findByCampaignIdAndUserId(campaignId, userId).map(CouponEntity::toDomain);
    }

    @Override
    public List<Coupon> findAllOwnedBy(Long userId) {
        return couponJpaRepository.findByUserIdOrderByIssuedAtDescIdDesc(userId).stream()
            .map(CouponEntity::toDomain)
            .toList();
    }

    @Override
    public Set<Long> issuedUserIdsAmong(Long campaignId, Collection<Long> userIds) {
        if (userIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(couponJpaRepository.userIdsAmong(campaignId, userIds));
    }

    @Override
    public List<Coupon> findIssuedAfterId(Long campaignId, long afterId, int limit) {
        return couponJpaRepository
            .findByCampaignIdAndIdGreaterThanOrderByIdAsc(campaignId, afterId, PageRequest.of(0, limit))
            .stream()
            .map(CouponEntity::toDomain)
            .toList();
    }
}
