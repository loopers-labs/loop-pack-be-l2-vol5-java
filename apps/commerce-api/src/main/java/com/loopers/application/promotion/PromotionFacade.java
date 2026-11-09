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

    /** 선착순 쿠폰 발급. 한 TX 안에서 발급 내역 insert → 남은 수량 조건 갱신 (DR-58). */
    @Transactional
    public PromotionInfo.Issued issue(Long requesterId, Long promotionId) {
        userService.getUser(requesterId);
        return PromotionInfo.Issued.from(promotionService.issue(requesterId, promotionId));
    }
}
