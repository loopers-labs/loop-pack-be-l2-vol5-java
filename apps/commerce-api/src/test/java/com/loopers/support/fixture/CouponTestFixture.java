package com.loopers.support.fixture;

import com.loopers.coupon.domain.CouponCampaign;
import jakarta.persistence.EntityManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Test database setup helpers for coupons. */
public class CouponTestFixture {

    private final EntityManager entityManager;
    private final TransactionTemplate transactionTemplate;

    public CouponTestFixture(EntityManager entityManager, PlatformTransactionManager transactionManager) {
        this.entityManager = entityManager;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public CouponCampaign campaign(String name, String benefit, boolean issuable, long totalQuota) {
        CouponCampaign campaign = new CouponCampaign(name, benefit, issuable, totalQuota);
        transactionTemplate.executeWithoutResult(status -> entityManager.persist(campaign));
        return campaign;
    }

    public void stopIssuance(Long campaignId) {
        transactionTemplate.executeWithoutResult(status ->
            entityManager.find(CouponCampaign.class, campaignId).stopIssuance()
        );
    }

    public long issuedCount(Long campaignId) {
        return transactionTemplate.execute(status ->
            entityManager.find(CouponCampaign.class, campaignId).getIssuedCount()
        );
    }

    public long couponCount(Long campaignId) {
        return transactionTemplate.execute(status -> entityManager.createQuery(
                "select count(c) from Coupon c where c.campaignId = :campaignId", Long.class)
            .setParameter("campaignId", campaignId)
            .getSingleResult()
        );
    }

    public Long couponId(Long campaignId, Long userId) {
        return transactionTemplate.execute(status -> entityManager.createQuery(
                "select c.id from Coupon c where c.campaignId = :campaignId and c.userId = :userId", Long.class)
            .setParameter("campaignId", campaignId)
            .setParameter("userId", userId)
            .getSingleResult()
        );
    }
}
