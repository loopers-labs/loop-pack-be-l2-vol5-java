package com.loopers.infrastructure.persistence.coupon.repository;

import com.loopers.domain.coupon.model.CouponSlot;
import com.loopers.domain.coupon.repository.CouponSlotRepository;
import com.loopers.infrastructure.persistence.coupon.entity.CouponSlotEntityMapper;
import com.loopers.infrastructure.persistence.coupon.entity.CouponSlotJpaEntity;
import com.loopers.infrastructure.persistence.coupon.jpa.CouponSlotJpaRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class CouponSlotRepositoryImpl implements CouponSlotRepository {
    private final CouponSlotJpaRepository jpaRepository;
    private final CouponSlotEntityMapper mapper;

    @Override
    public CouponSlot save(CouponSlot slot) {
        CouponSlotJpaEntity entity;
        if (slot.getId() == null) {
            entity = mapper.toNewEntity(slot);
        } else {
            entity = jpaRepository.findById(slot.getId()).orElseThrow();
            mapper.apply(slot, entity);
        }
        return mapper.toDomain(jpaRepository.saveAndFlush(entity));
    }

    @Override
    public Optional<CouponSlot> findAvailableForUpdateSkipLocked(long campaignId) {
        return jpaRepository.findAvailableForUpdateSkipLocked(campaignId).map(mapper::toDomain);
    }

    @Override
    public long countByCampaignId(long campaignId) {
        return jpaRepository.countByCampaignId(campaignId);
    }
}
