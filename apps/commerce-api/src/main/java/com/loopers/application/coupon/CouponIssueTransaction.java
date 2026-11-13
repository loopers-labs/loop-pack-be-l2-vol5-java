package com.loopers.application.coupon;

import com.loopers.domain.coupon.CouponCampaign;
import com.loopers.domain.coupon.CouponCampaignRepository;
import com.loopers.domain.coupon.IssuedCoupon;
import com.loopers.domain.coupon.IssuedCouponRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * DB 발급 TX 하나(docs/week8/decisions.md D-8, INV-801, INV-802). 순서만 정하고, 발급 판단은 {@link CouponCampaign}이 한다.
 *
 * <ol>
 *   <li>캠페인 행을 {@code FOR UPDATE}로 읽는다. 없으면 404.</li>
 *   <li>이 사용자의 쿠폰이 이미 있으면 그것을 EXISTING으로 돌려준다 — 발급 수를 올리지 않는다. 판단(품절·비활성)보다 먼저 본다.
 *       이미 받은 사람은 캠페인이 그 뒤 품절·비활성이 돼도 같은 쿠폰을 받는다.
 *       락을 잡은 뒤에 보므로 같은 사용자의 앞선 TX가 commit한 쿠폰이 보인다(InnoDB는 TX의 첫 일반 조회에서 스냅샷을 만든다).</li>
 *   <li>캠페인이 판단하고 발급 수를 올린 뒤 혜택을 복사한 쿠폰을 만든다. 쿠폰을 저장하고 바로 flush한다.</li>
 * </ol>
 *
 * <p>쿠폰 저장이 (campaign_id, user_id) unique에 걸리면 IssuedCouponDuplicateException이 나가며 TX 전체(발급 수 증가 포함)가
 * rollback된다. 기존 쿠폰 조회는 호출자({@link CouponIssuer})가 새 TX에서 한다 — 실패한 TX 안에서는 다시 읽을 수 없다.
 * 다른 오류도 TX 전체를 rollback하고 그대로 나간다.
 */
@RequiredArgsConstructor
@Component
public class CouponIssueTransaction {

    private final CouponCampaignRepository couponCampaignRepository;
    private final IssuedCouponRepository issuedCouponRepository;
    private final Clock clock;

    @Transactional
    public CouponIssueResult issue(Long campaignId, Long userId) {
        CouponCampaign campaign = couponCampaignRepository.findByIdForUpdate(campaignId)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "[id = " + campaignId + "] 쿠폰 캠페인을 찾을 수 없습니다."));

        Optional<IssuedCoupon> existing = issuedCouponRepository.findByCampaignIdAndUserId(campaignId, userId);
        if (existing.isPresent()) {
            return CouponIssueResult.existing(IssuedCouponInfo.from(existing.get()));
        }

        // µs로 자른다 — DB 컬럼(datetime(6))과 같은 정밀도라 응답한 시각과 저장된 시각이 같다.
        IssuedCoupon coupon = campaign.issueTo(userId, ZonedDateTime.now(clock).truncatedTo(ChronoUnit.MICROS));
        IssuedCoupon saved = issuedCouponRepository.save(coupon);
        couponCampaignRepository.save(campaign);
        return CouponIssueResult.created(IssuedCouponInfo.from(saved));
    }
}
