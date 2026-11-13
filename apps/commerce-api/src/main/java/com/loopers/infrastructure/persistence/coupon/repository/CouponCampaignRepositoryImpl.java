package com.loopers.infrastructure.persistence.coupon.repository;

import com.loopers.domain.coupon.model.CouponCampaign;
import com.loopers.domain.coupon.repository.CouponCampaignRepository;
import com.loopers.infrastructure.persistence.coupon.entity.CouponCampaignEntityMapper;
import com.loopers.infrastructure.persistence.coupon.entity.CouponCampaignJpaEntity;
import com.loopers.infrastructure.persistence.coupon.jpa.CouponCampaignJpaRepository;
import com.loopers.infrastructure.persistence.coupon.support.JdbcCouponCampaignIssueLock;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class CouponCampaignRepositoryImpl implements CouponCampaignRepository {
    private final CouponCampaignJpaRepository jpaRepository;
    private final CouponCampaignEntityMapper mapper;
    private final EntityManager entityManager;
    private final JdbcCouponCampaignIssueLock issueLock;

    @Override
    public CouponCampaign save(CouponCampaign campaign) {
        CouponCampaignJpaEntity entity;
        if (campaign.getId() == null) {
            entity = mapper.toNewEntity(campaign);
        } else {
            entity = jpaRepository.findById(campaign.getId()).orElseThrow();
            mapper.apply(campaign, entity);
        }
        return mapper.toDomain(jpaRepository.saveAndFlush(entity));
    }

    @Override
    public Optional<CouponCampaign> findById(long campaignId) {
        return jpaRepository.findById(campaignId).map(mapper::toDomain);
    }

    @Override
    public Optional<CouponCampaign> findByIdForUpdate(long campaignId) {
        return jpaRepository.findByIdForUpdate(campaignId).map(entity -> {
            // 기존 일반 조회로 영속성 컨텍스트에 남은 값도 잠근 현재 행으로 갱신한다.
            entityManager.refresh(entity, LockModeType.PESSIMISTIC_WRITE);
            return mapper.toDomain(entity);
        });
    }

    @Override
    public Optional<CouponCampaign> findByIdForIssue(long campaignId) {
        return issueLock.withShortWait(() -> findByIdForUpdate(campaignId));
    }
}
