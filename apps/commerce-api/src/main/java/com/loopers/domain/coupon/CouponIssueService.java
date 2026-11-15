package com.loopers.domain.coupon;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;

import java.util.Optional;

/**
 * 쿠폰 발급의 DB 규칙. Redis를 모르고, 이 클래스 자체는 트랜잭션을 열지 않는다(트랜잭션은 CouponIssueTransactions).
 *
 * <ol>
 *   <li>이미 받은 쿠폰이면 캠페인이 품절·비활성이어도 같은 쿠폰을 돌려준다(created=false)</li>
 *   <li>캠페인이 없거나 비활성·품절이면 락 없이 읽어 바로 거절한다. 최종 판정이 아니라 빠른 거절이다</li>
 *   <li>INSERT → 조건부 UPDATE를 한 트랜잭션으로 실행한다. 실패하면 전체 롤백한다</li>
 *   <li>유일 제약 예외가 나면 새 트랜잭션에서 기존 쿠폰을 조회해 돌려준다. 쿠폰이 실제로 있을 때만 중복으로 본다</li>
 *   <li>DB 연결·timeout 오류는 503으로 바꾼다. 중복 성공으로 바꾸지 않는다</li>
 * </ol>
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class CouponIssueService {

    private final CouponIssueTransactions transactions;

    /**
     * 이미 받은 쿠폰을 조회한다. 자리(세마포어)를 차지하지 않는 가벼운 읽기이며, 있으면 호출자는 신규 발급 경로에 들어가지 않는다.
     * 캠페인이 품절·비활성이 된 뒤에도 이미 받은 쿠폰은 같은 결과(created=false)로 돌려준다.
     */
    public Optional<CouponIssueResult> findExisting(Long campaignId, Long userId) {
        try {
            return transactions.findCoupon(campaignId, userId).map(coupon -> new CouponIssueResult(coupon.getId(), false));
        } catch (DataAccessException | TransactionException e) {
            log.error("기존 쿠폰 조회 중 DB 오류: campaignId={}, userId={}", campaignId, userId, e);
            throw new CoreException(ErrorType.SERVICE_UNAVAILABLE);
        }
    }

    public CouponIssueResult issue(Long campaignId, Long userId) {
        try {
            return doIssue(campaignId, userId);
        } catch (CoreException e) {
            throw e;
        } catch (DataAccessException | TransactionException e) {
            log.error("쿠폰 발급 중 DB 오류: campaignId={}, userId={}", campaignId, userId, e);
            throw new CoreException(ErrorType.SERVICE_UNAVAILABLE);
        }
    }

    private CouponIssueResult doIssue(Long campaignId, Long userId) {
        var existing = transactions.findCoupon(campaignId, userId);
        if (existing.isPresent()) {
            return new CouponIssueResult(existing.get().getId(), false);
        }

        CouponCampaignModel campaign = transactions.findCampaign(campaignId)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "존재하지 않는 캠페인입니다."));
        rejectIfNotIssuable(campaign);

        try {
            Long couponId = transactions.insertAndIncrease(campaignId, userId, campaign.getBenefitDescription());
            return new CouponIssueResult(couponId, true);
        } catch (DataIntegrityViolationException e) {
            // 롤백된 뒤 새 트랜잭션에서 확인한다. 같은 사용자의 쿠폰이 실제로 있을 때만 중복이다.
            return transactions.findCoupon(campaignId, userId)
                .map(coupon -> new CouponIssueResult(coupon.getId(), false))
                .orElseThrow(() -> e);
        } catch (CouponIssueTransactions.QuotaNotAcquiredException e) {
            // INSERT까지 롤백됐다. 지금의 캠페인 상태로 품절과 비활성을 구분한다.
            CouponCampaignModel latest = transactions.findCampaign(campaignId)
                .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "존재하지 않는 캠페인입니다."));
            rejectIfNotIssuable(latest);
            // 거절 사유를 확인하지 못했다면 한도를 넘겼다고 단정하지 않고 일시 오류로 본다.
            throw new CoreException(ErrorType.SERVICE_UNAVAILABLE);
        }
    }

    private void rejectIfNotIssuable(CouponCampaignModel campaign) {
        if (!campaign.isActive()) {
            throw new CoreException(ErrorType.CAMPAIGN_INACTIVE);
        }
        if (campaign.isSoldOut()) {
            throw new CoreException(ErrorType.COUPON_SOLD_OUT);
        }
    }
}
