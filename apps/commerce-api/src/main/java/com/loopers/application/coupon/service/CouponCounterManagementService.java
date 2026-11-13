package com.loopers.application.coupon.service;

import com.loopers.application.coupon.port.CouponCounterRebuildStore;
import com.loopers.application.coupon.query.CouponCounterSnapshotDao;
import com.loopers.application.coupon.support.CouponCounterException;
import com.loopers.application.support.error.ApplicationErrorCode;
import com.loopers.application.support.error.ApplicationException;
import com.loopers.domain.coupon.model.CouponCampaign;
import com.loopers.domain.coupon.repository.CouponCampaignRepository;
import com.loopers.domain.coupon.repository.CouponIssueCounter;
import java.util.List;
import java.util.OptionalLong;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CouponCounterManagementService {
    public record Comparison(long databaseCount, OptionalLong aggregateCount) {}

    private final CouponCampaignRepository campaigns;
    private final CouponCounterSnapshotDao snapshots;
    private final CouponCounterRebuildStore rebuildStore;
    private final CouponIssueCounter counter;

    /** 모든 발급 TX의 마지막 캠페인 잠금과 직렬화한다. Redis 대기는 command timeout으로 제한된다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public long rebuild(long campaignId) {
        CouponCampaign campaign = campaigns.findByIdForIssue(campaignId)
            .orElseThrow(() -> new ApplicationException(ApplicationErrorCode.COUPON_CAMPAIGN_NOT_FOUND));
        List<Long> ids = snapshots.findCommittedCouponIds(campaignId);
        if (ids.size() != campaign.getIssuedCount()) {
            throw new CouponCounterException("Coupon DB count does not match committed results");
        }
        rebuildStore.replace(campaignId, ids);
        return ids.size();
    }

    /** 두 저장소의 단일 원자 snapshot은 아니다. 쓰기 중 차이가 있으면 통제된 재집계로 확인한다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Comparison compare(long campaignId) {
        CouponCampaign campaign = campaigns.findById(campaignId)
            .orElseThrow(() -> new ApplicationException(ApplicationErrorCode.COUPON_CAMPAIGN_NOT_FOUND));
        return new Comparison(campaign.getIssuedCount(), counter.findIssuedCount(campaignId));
    }
}
