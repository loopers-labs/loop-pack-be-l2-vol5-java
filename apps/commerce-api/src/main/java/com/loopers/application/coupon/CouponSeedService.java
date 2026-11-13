package com.loopers.application.coupon;

import com.loopers.application.time.ApplicationTime;
import com.loopers.domain.coupon.CouponCampaign;
import com.loopers.domain.coupon.CouponCampaignRepository;
import com.loopers.domain.coupon.CouponRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CouponSeedService {

    private static final String SEED_NAME = "local-coupon-seed";
    private static final int SEED_QUOTA = 100;
    private static final Duration ISSUANCE_PERIOD = Duration.ofDays(1);

    private final CouponCampaignRepository campaigns;
    private final CouponRepository coupons;
    private final ApplicationTime time;
    private final CouponIssueQueue queue;

    // 로컬 runner 전용이다. 여러 인스턴스의 동시 seed 초기화는 지원하지 않는다.
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CouponCampaign prepare(boolean reset) {
        List<CouponCampaign> candidates = campaigns.findAllByName(SEED_NAME);
        if (candidates.size() > 1) {
            throw inconsistentSeed("로컬 seed 이름에 해당하는 캠페인이 여러 개입니다.");
        }
        if (candidates.isEmpty()) {
            return createSeed();
        }
        CouponCampaign campaign = candidates.getFirst();
        if (!reset) {
            if (coupons.countIssuedByCampaignId(campaign.getId()) != campaign.getIssuedCount()) {
                throw inconsistentSeed("로컬 seed의 실제 발급 결과와 발급 수가 일치하지 않습니다.");
            }
            return campaign;
        }
        clearQueueBeforeReset(campaign.getId());
        Instant resetNow = time.now().toInstant();
        // 실제 발급과 같은 순서로 캠페인 잠금을 먼저 얻고, 같은 트랜잭션에서 그 캠페인의 쿠폰을 지운다.
        if (campaigns.resetSeedState(campaign.getId(), resetNow, resetNow.plus(ISSUANCE_PERIOD), resetNow) != 1) {
            throw inconsistentSeed("로컬 seed 캠페인을 초기화할 수 없습니다.");
        }
        coupons.deleteByCampaignId(campaign.getId());
        return campaigns.findById(campaign.getId()).orElseThrow(() -> inconsistentSeed("로컬 seed가 없습니다."));
    }

    /**
     * DB를 바꾸기 전에 seed 캠페인의 대기열 정보를 먼저 지운다. 남은 실행이 있거나 Redis를 확인할 수 없으면
     * DB를 바꾸지 않고 실패한다. Redis를 먼저 정리하면 DB 초기화가 실패해도 옛 성공 이력과 초기화된 DB가 함께 남지 않는다.
     * 로컬 단일 인스턴스 시작 단계에서만 쓰므로 DB 트랜잭션 안에서 Redis를 호출한다.
     */
    private void clearQueueBeforeReset(Long campaignId) {
        CouponIssueQueueCleanup cleanup;
        try {
            cleanup = queue.clearCampaign(campaignId);
        } catch (CouponIssueQueueUnavailableException | CouponIssueQueueScriptException exception) {
            throw inconsistentSeed("쿠폰 발급 대기열 상태를 확인할 수 없어 seed reset을 거절합니다.");
        }
        if (cleanup instanceof CouponIssueQueueCleanup.Blocked blocked) {
            throw inconsistentSeed("남은 대기열 작업이나 실행 token이 있어 seed reset을 거절합니다. reason=" + blocked.reason());
        }
    }

    private CouponCampaign createSeed() {
        Instant startAt = time.now().toInstant();
        // 쿠폰 행은 발급할 때 insert하므로 캠페인만 만든다.
        return campaigns.save(CouponCampaign.create(
            SEED_NAME, SEED_QUOTA, "5,000원 할인권", startAt, startAt.plus(ISSUANCE_PERIOD)));
    }

    private CoreException inconsistentSeed(String message) {
        return new CoreException(ErrorType.INTERNAL_ERROR, message);
    }
}
