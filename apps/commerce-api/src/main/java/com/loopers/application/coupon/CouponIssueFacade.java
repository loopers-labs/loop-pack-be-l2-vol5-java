package com.loopers.application.coupon;

import com.loopers.application.time.ApplicationTime;
import com.loopers.domain.coupon.Coupon;
import com.loopers.domain.coupon.CouponCampaign;
import com.loopers.domain.coupon.CouponCampaignRepository;
import com.loopers.domain.coupon.CouponRepository;
import com.loopers.domain.coupon.DuplicateCouponIssueException;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class CouponIssueFacade {

    private final CouponRepository coupons;
    private final CouponCampaignRepository campaigns;
    private final CouponIssuePermits permits;
    private final CouponIssueTransaction transaction;
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

        CouponIssueTransactionCompletion completion = new CouponIssueTransactionCompletion();
        CouponIssueInfo issued;
        try {
            issued = transaction.issue(campaignId, userId, completion);
        } catch (RuntimeException | Error failure) {
            returnPermitIfDbWorkDone(campaignId, token, completion, failure);
            if (failure instanceof DuplicateCouponIssueException) {
                Coupon concurrentResult = coupons.findIssuedByCampaignIdAndUserId(campaignId, userId)
                    .orElseThrow(() -> new CoreException(ErrorType.INTERNAL_ERROR,
                        "중복 발급 충돌 뒤 기존 쿠폰을 찾지 못했습니다."));
                return new CouponIssueResult(toInfo(concurrentResult), false);
            }
            throw failure;
        }
        returnPermitSafely(token);
        return new CouponIssueResult(issued, true);
    }

    public List<CouponIssueInfo> getMyCoupons(Long userId) {
        return coupons.findAllIssuedByUserId(userId).stream().map(this::toInfo).toList();
    }

    private CouponIssueInfo toInfo(Coupon coupon) {
        CouponCampaign campaign = campaigns.findById(coupon.getCampaignId())
            .orElseThrow(() -> new CoreException(ErrorType.INTERNAL_ERROR, "쿠폰의 캠페인 정보를 찾지 못했습니다."));
        return CouponIssueInfo.from(coupon, campaign);
    }

    /**
     * 예외 종류가 아니라 트랜잭션 완료 상태로 DB 작업의 종료를 판단한다.
     * 끝났는지 확인하지 못한 실행의 token은 자리를 계속 점유하게 두고, 정리할 수 있도록 내부 실행 ID인 token을 기록한다.
     */
    private void returnPermitIfDbWorkDone(Long campaignId, String token,
                                          CouponIssueTransactionCompletion completion, Throwable failure) {
        if (completion.isDbWorkDone()) {
            returnPermitSafely(token);
            return;
        }
        log.error("발급 트랜잭션의 DB 작업 종료를 확인하지 못해 permit을 반환하지 않습니다. "
            + "작업 종료와 발급 수를 확인한 뒤 이 token만 정리해야 합니다. campaignId={}, permitToken={}",
            campaignId, token, failure);
    }

    private void returnPermitSafely(String token) {
        try {
            if (permits.release(token) != 1) {
                closeAfterReturnProblem("permit을 반환하지 못해 신규 발급을 닫았습니다.", null);
            }
        } catch (RuntimeException exception) {
            closeAfterReturnProblem("permit 반환 중 오류가 발생해 신규 발급을 닫았습니다.", exception);
        }
    }

    private void closeAfterReturnProblem(String message, RuntimeException exception) {
        admission.close();
        if (exception == null) {
            log.error(message);
        } else {
            log.error(message, exception);
        }
    }
}
