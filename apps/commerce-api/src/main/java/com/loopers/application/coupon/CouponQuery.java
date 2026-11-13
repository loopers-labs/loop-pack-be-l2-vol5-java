package com.loopers.application.coupon;

import com.loopers.domain.coupon.IssuedCouponRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 발급된 쿠폰 조회. DB만 읽는다 — 수용 상태(Redis)가 닫혀도 이미 받은 쿠폰과 보유 목록은 계속 제공한다(docs/week8/decisions.md D-2).
 */
@RequiredArgsConstructor
@Component
public class CouponQuery {

    private final IssuedCouponRepository issuedCouponRepository;

    /**
     * 그 사용자가 그 캠페인에서 받은 쿠폰. 자리를 잡기 전 "이미 끝난 사람을 빨리 돌려보내는" 지름길과,
     * unique 위반 뒤 새 TX에서 기존 쿠폰을 찾는 데 쓴다(D-8). "없음"을 봤다고 중복이 막히지는 않는다(check-then-act).
     */
    @Transactional(readOnly = true)
    public Optional<IssuedCouponInfo> findIssued(Long campaignId, Long userId) {
        return issuedCouponRepository.findByCampaignIdAndUserId(campaignId, userId).map(IssuedCouponInfo::from);
    }

    /** 요청자 것만, 발급 시각 내림차순, 같으면 couponId 내림차순(D-16, INV-806). */
    @Transactional(readOnly = true)
    public List<IssuedCouponInfo> listMine(Long userId) {
        return issuedCouponRepository.findAllByUserId(userId).stream()
            .map(IssuedCouponInfo::from)
            .toList();
    }
}
