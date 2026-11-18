package com.loopers.application.promotion;

import com.loopers.domain.promotion.IssuedCouponModel;
import com.loopers.domain.promotion.PromotionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 선착순 쿠폰 발급의 트랜잭션 단위. 발급 내역 insert → 남은 수량 조건 갱신 두 문장만 한 TX 로 묶는다 (DR-58).
 * 사용자·프로모션 상태 확인은 이 TX 밖에서 Facade 가 먼저 한다.
 */
@RequiredArgsConstructor
@Component
public class CouponIssueProcessor {
    private final PromotionService promotionService;

    @Transactional
    public IssuedCouponModel issue(Long userId, Long promotionId) {
        return promotionService.issue(userId, promotionId);
    }
}
