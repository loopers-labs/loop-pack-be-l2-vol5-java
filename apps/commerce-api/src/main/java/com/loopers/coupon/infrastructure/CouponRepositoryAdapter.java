package com.loopers.coupon.infrastructure;

import com.loopers.coupon.domain.Coupon;
import com.loopers.coupon.domain.CouponRepository;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class CouponRepositoryAdapter implements CouponRepository {

    private final CouponJpaRepository jpaRepository;
    private final EntityManager entityManager;

    public CouponRepositoryAdapter(CouponJpaRepository jpaRepository, EntityManager entityManager) {
        this.jpaRepository = jpaRepository;
        this.entityManager = entityManager;
    }

    @Override
    public Coupon save(Coupon coupon) {
        if (coupon.getId() == 0L) {
            entityManager.persist(coupon);
            return coupon;
        }
        return jpaRepository.save(coupon);
    }

    @Override
    public Optional<Coupon> findByCampaignIdAndUserId(Long campaignId, Long userId) {
        return jpaRepository.findByCampaignIdAndUserId(campaignId, userId);
    }

    @Override
    public List<Coupon> findAllByUserId(Long userId) {
        return jpaRepository.findAllByUserIdOrderByIssuedAtDescIdDesc(userId);
    }
}
