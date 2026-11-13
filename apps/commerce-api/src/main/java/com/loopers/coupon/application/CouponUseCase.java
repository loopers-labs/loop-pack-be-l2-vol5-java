package com.loopers.coupon.application;

import com.loopers.coupon.domain.Coupon;
import com.loopers.coupon.domain.CouponCampaignRepository;
import com.loopers.coupon.domain.CouponRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorCode;
import com.loopers.user.domain.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class CouponUseCase {

    private final CouponCampaignRepository campaignRepository;
    private final CouponRepository couponRepository;
    private final UserRepository userRepository;
    private final CouponAdmission admission;
    private final CouponIssueTransaction issueTransaction;

    public CouponUseCase(
        CouponCampaignRepository campaignRepository,
        CouponRepository couponRepository,
        UserRepository userRepository,
        CouponAdmission admission,
        CouponIssueTransaction issueTransaction
    ) {
        this.campaignRepository = campaignRepository;
        this.couponRepository = couponRepository;
        this.userRepository = userRepository;
        this.admission = admission;
        this.issueTransaction = issueTransaction;
    }

    public CouponIssueResult issue(Long campaignId, Long userId) {
        campaignRepository.findById(campaignId)
            .orElseThrow(() -> new CoreException(ErrorCode.COUPON_CAMPAIGN_NOT_FOUND));
        requireUser(userId);

        Optional<Coupon> existing = couponRepository.findByCampaignIdAndUserId(campaignId, userId);
        if (existing.isPresent()) {
            return new CouponIssueResult(existing.get(), false);
        }

        String token = admission.acquire()
            .orElseThrow(() -> new CoreException(ErrorCode.COUPON_ADMISSION_FULL));
        CouponIssueResult result;
        try {
            result = issueTransaction.issue(campaignId, userId);
        } catch (RuntimeException exception) {
            releaseAfterFailure(token, exception);
            throw exception;
        }
        admission.release(token);
        return result;
    }

    @Transactional(readOnly = true)
    public List<Coupon> getMyCoupons(Long userId) {
        requireUser(userId);
        return couponRepository.findAllByUserId(userId);
    }

    private void requireUser(Long userId) {
        if (userRepository.findById(userId).isEmpty()) {
            throw new CoreException(ErrorCode.USER_NOT_IDENTIFIED);
        }
    }

    private void releaseAfterFailure(String token, RuntimeException failure) {
        try {
            admission.release(token);
        } catch (RuntimeException releaseFailure) {
            failure.addSuppressed(releaseFailure);
        }
    }

    public record CouponIssueResult(Coupon coupon, boolean created) {
    }
}
