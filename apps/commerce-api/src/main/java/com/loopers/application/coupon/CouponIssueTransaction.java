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
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class CouponIssueTransaction {

    private final CouponCampaignRepository campaigns;
    private final CouponRepository coupons;
    private final ApplicationTime applicationTime;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CouponIssueInfo issue(Long campaignId, Long userId, CouponIssueTransactionCompletion completion) {
        // permit 반환 판단을 위해 발급 SQL보다 먼저 이 트랜잭션의 완료 상태를 기록하게 한다.
        completion.watchCurrentTransaction();
        var issueNow = applicationTime.now().toInstant();

        // UPDATE가 먼저 잠금을 얻고 최종 기간·잔여 수량 조건을 판정한다.
        int updated = campaigns.incrementIssuedCountIfIssuable(campaignId, issueNow);
        if (updated == 0) {
            if (coupons.findIssuedByCampaignIdAndUserId(campaignId, userId).isPresent()) {
                throw new DuplicateCouponIssueException(null);
            }
            CouponCampaign current = campaigns.findById(campaignId)
                .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "쿠폰 캠페인을 찾을 수 없습니다."));
            current.validateIssuable(issueNow);
            throw new CoreException(ErrorType.INTERNAL_ERROR, "발급 가능한 캠페인의 카운터를 갱신하지 못했습니다.");
        }
        if (updated != 1) {
            throw new CoreException(ErrorType.INTERNAL_ERROR, "캠페인 발급 수를 한 번만 갱신해야 합니다.");
        }

        CouponCampaign campaign = campaigns.findById(campaignId)
            .orElseThrow(() -> new CoreException(ErrorType.INTERNAL_ERROR, "발급한 캠페인을 다시 읽지 못했습니다."));
        if (coupons.findIssuedByCampaignIdAndUserId(campaignId, userId).isPresent()) {
            throw new DuplicateCouponIssueException(null);
        }

        // 카운터 갱신과 같은 트랜잭션에서 발급 기록을 insert한다. 같은 사용자 충돌은 유일 제약이 최종으로 막는다.
        Coupon issued = coupons.insert(Coupon.create(campaignId, userId, issueNow));
        return CouponIssueInfo.from(issued, campaign);
    }
}
