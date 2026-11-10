package com.loopers.application.promotion;

import com.loopers.domain.promotion.PromotionService;
import com.loopers.domain.promotion.PromotionType;
import com.loopers.domain.user.UserService;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Component
public class PromotionFacade {
    private final UserService userService;
    private final PromotionService promotionService;
    private final CouponIssueProcessor couponIssueProcessor;
    private final CouponIssueLimiter couponIssueLimiter;

    /** 관리자 프로모션 생성. 지금 종류는 COUPON_ISSUE 하나이고, 만들면 바로 ACTIVE 다 (DR-56). */
    @Transactional
    public PromotionInfo.Created create(Long requesterId, PromotionCommand command) {
        userService.getAdmin(requesterId);
        if (!PromotionType.COUPON_ISSUE.name().equals(command.type())) {
            throw new CoreException(ErrorType.INVALID_PROMOTION, "지원하는 프로모션 종류는 COUPON_ISSUE 입니다. [type = " + command.type() + "]");
        }
        PromotionCommand.CouponIssue couponIssue = command.couponIssue();
        if (couponIssue == null || couponIssue.totalQuantity() == null || couponIssue.perUserLimit() == null) {
            throw new CoreException(ErrorType.INVALID_PROMOTION, "쿠폰 발급 설정(totalQuantity·perUserLimit)이 없습니다.");
        }
        return PromotionInfo.Created.from(promotionService.createCouponIssue(
            command.name(), command.startsAt(), command.endsAt(),
            couponIssue.totalQuantity(), couponIssue.perUserLimit(), couponIssue.couponSpec()));
    }

    /**
     * 선착순 쿠폰 발급. 사용자·프로모션 상태 확인은 TX 없이 읽고,
     * 발급 내역 insert → 남은 수량 조건 갱신 두 문장만 CouponIssueProcessor 의 TX 로 묶는다 (DR-58).
     * 이미 받은 사용자는 허가를 얻기 전에 돌려보내고, 발급 TX 만 세마포어 허가 안에서 실행한다 (DR-60).
     */
    public PromotionInfo.Issued issue(Long requesterId, Long promotionId) {
        userService.getUser(requesterId);
        promotionService.getIssuable(promotionId);
        promotionService.checkNotIssued(requesterId, promotionId);
        return PromotionInfo.Issued.from(
            couponIssueLimiter.run(promotionId, () -> couponIssueProcessor.issue(requesterId, promotionId)));
    }
}
