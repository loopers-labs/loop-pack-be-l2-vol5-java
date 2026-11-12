package com.loopers.application.coupon;

import com.loopers.application.time.ApplicationTime;
import com.loopers.domain.coupon.Coupon;
import com.loopers.domain.coupon.CouponCampaign;
import com.loopers.domain.coupon.CouponCampaignRepository;
import com.loopers.domain.coupon.CouponRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class CouponIssueFacade {

    private final CouponRepository coupons;
    private final CouponCampaignRepository campaigns;
    private final CouponIssuePermits permits;
    private final CouponIssueExecutor executor;
    private final CouponIssueAdmissionState admission;
    private final CouponIssueProperties properties;
    private final ApplicationTime applicationTime;

    public CouponIssueResult issue(Long campaignId, Long userId) {
        Coupon existing = coupons.findIssuedByCampaignIdAndUserId(campaignId, userId).orElse(null);
        if (existing != null) {
            return new CouponIssueResult(toInfo(existing), false);
        }

        CouponCampaign campaign = campaigns.findById(campaignId)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "쿠폰 캠페인을 찾을 수 없습니다."));
        campaign.validateIssuable(applicationTime.now().toInstant());
        admission.requireOpen();

        String token = UUID.randomUUID().toString();
        boolean acquired;
        try {
            acquired = permits.tryAcquire(token, properties.requireCapacity());
        } catch (CouponPermitScriptException exception) {
            admission.close();
            throw new CoreException(ErrorType.INTERNAL_ERROR, "쿠폰 처리 자리 스크립트 오류로 신규 발급을 닫았습니다.");
        } catch (RuntimeException exception) {
            admission.close();
            throw new CoreException(ErrorType.SERVICE_UNAVAILABLE, "쿠폰 처리 자리 상태를 확인할 수 없어 신규 발급을 닫았습니다.");
        }
        if (!acquired) {
            throw new CoreException(ErrorType.TOO_MANY_REQUESTS, "현재 쿠폰 발급 처리 자리가 없습니다.");
        }

        return switch (executor.execute(campaignId, userId, token)) {
            case CouponIssueExecution.Issued issued -> new CouponIssueResult(issued.coupon(), true);
            case CouponIssueExecution.AlreadyIssued already -> new CouponIssueResult(toInfo(already.coupon()), false);
            case CouponIssueExecution.Failed failed -> throw failed.failure();
        };
    }

    public List<CouponIssueInfo> getMyCoupons(Long userId) {
        return coupons.findAllIssuedByUserId(userId).stream().map(this::toInfo).toList();
    }

    private CouponIssueInfo toInfo(Coupon coupon) {
        CouponCampaign campaign = campaigns.findById(coupon.getCampaignId())
            .orElseThrow(() -> new CoreException(ErrorType.INTERNAL_ERROR, "쿠폰의 캠페인 정보를 찾지 못했습니다."));
        return CouponIssueInfo.from(coupon, campaign);
    }
}
