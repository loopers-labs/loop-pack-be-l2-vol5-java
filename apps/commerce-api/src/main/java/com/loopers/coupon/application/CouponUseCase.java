package com.loopers.coupon.application;

import com.loopers.coupon.domain.Coupon;
import com.loopers.coupon.domain.CouponCampaign;
import com.loopers.coupon.domain.CouponCampaignRepository;
import com.loopers.coupon.domain.CouponRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorCode;
import com.loopers.user.domain.UserRepository;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.List;

@Service
public class CouponUseCase {

    private final CouponCampaignRepository campaignRepository;
    private final CouponRepository couponRepository;
    private final UserRepository userRepository;
    private final EntityManager entityManager;

    public CouponUseCase(
        CouponCampaignRepository campaignRepository,
        CouponRepository couponRepository,
        UserRepository userRepository,
        EntityManager entityManager
    ) {
        this.campaignRepository = campaignRepository;
        this.couponRepository = couponRepository;
        this.userRepository = userRepository;
        this.entityManager = entityManager;
    }

    @Transactional
    public CouponIssueResult issue(Long campaignId, Long userId) {
        CouponCampaign campaign = campaignRepository.findForIssueById(campaignId)
            .orElseThrow(() -> new CoreException(ErrorCode.COUPON_CAMPAIGN_NOT_FOUND));
        requireUser(userId);

        return couponRepository.findByCampaignIdAndUserId(campaignId, userId)
            .map(existing -> new CouponIssueResult(existing, false))
            .orElseGet(() -> issueNewCoupon(campaign, userId));
    }

    @Transactional(readOnly = true)
    public List<Coupon> getMyCoupons(Long userId) {
        requireUser(userId);
        return couponRepository.findAllByUserId(userId);
    }

    private CouponIssueResult issueNewCoupon(CouponCampaign campaign, Long userId) {
        campaign.issue();
        Coupon coupon = couponRepository.save(new Coupon(
            userId,
            campaign.getId(),
            campaign.getBenefit(),
            ZonedDateTime.now()
        ));
        entityManager.flush();
        return new CouponIssueResult(coupon, true);
    }

    private void requireUser(Long userId) {
        if (userRepository.findById(userId).isEmpty()) {
            throw new CoreException(ErrorCode.USER_NOT_IDENTIFIED);
        }
    }

    public record CouponIssueResult(Coupon coupon, boolean created) {
    }
}
