package com.loopers.application.coupon;

import com.loopers.application.time.ApplicationTime;
import com.loopers.domain.coupon.Coupon;
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
import java.util.stream.IntStream;

@Service
@RequiredArgsConstructor
public class CouponSeedService {

    private static final String SEED_NAME = "local-coupon-seed";
    private static final int SEED_QUOTA = 100;
    private static final Duration ISSUANCE_PERIOD = Duration.ofDays(1);

    private final CouponCampaignRepository campaigns;
    private final CouponRepository coupons;
    private final ApplicationTime time;

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
        if (campaign.getTotalQuota() != SEED_QUOTA || coupons.countByCampaignId(campaign.getId()) != SEED_QUOTA) {
            throw inconsistentSeed("로컬 seed의 쿠폰 행 수와 발급 한도가 일치하지 않습니다.");
        }
        if (!reset) {
            if (coupons.countIssuedByCampaignId(campaign.getId()) != campaign.getIssuedCount()) {
                throw inconsistentSeed("로컬 seed의 실제 발급 결과와 발급 수가 일치하지 않습니다.");
            }
            return campaign;
        }
        Instant resetNow = time.now().toInstant();
        // 실제 발급과 같은 순서로 캠페인 잠금을 먼저 얻고, 쿠폰 할당을 함께 초기화한다.
        if (campaigns.resetSeedState(campaign.getId(), resetNow, resetNow.plus(ISSUANCE_PERIOD), resetNow) != 1) {
            throw inconsistentSeed("로컬 seed 캠페인을 초기화할 수 없습니다.");
        }
        if (coupons.resetSeedAssignments(campaign.getId(), resetNow) != SEED_QUOTA) {
            throw inconsistentSeed("로컬 seed 쿠폰을 모두 초기화할 수 없습니다.");
        }
        return campaigns.findById(campaign.getId()).orElseThrow(() -> inconsistentSeed("로컬 seed가 없습니다."));
    }

    private CouponCampaign createSeed() {
        Instant startAt = time.now().toInstant();
        CouponCampaign campaign = campaigns.save(CouponCampaign.create(
            SEED_NAME, SEED_QUOTA, "5,000원 할인권", startAt, startAt.plus(ISSUANCE_PERIOD)));
        coupons.saveStock(IntStream.range(0, SEED_QUOTA).mapToObj(index -> Coupon.create(campaign.getId())).toList());
        return campaign;
    }

    private CoreException inconsistentSeed(String message) {
        return new CoreException(ErrorType.INTERNAL_ERROR, message);
    }
}
