package com.loopers.domain.promotion;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.util.Map;

/**
 * BC-05 프로모션 규칙. 발급 수가 발급 한도를 넘지 않는 것(TB-17)과 사용자당 한 장(TB-18 고유 제약)은
 * Model 하나로 못 지켜 여기서 DB 조건 갱신·고유 제약으로 지킨다 (DR-58). 트랜잭션은 호출하는 Facade 가 연다.
 */
@RequiredArgsConstructor
@Component
public class PromotionService {

    private final PromotionRepository promotionRepository;

    /** promotion·쿠폰 발급 설정·남은 수량을 함께 만들고 바로 ACTIVE 로 연다 (DR-56). */
    public CouponIssuePromotion createCouponIssue(String name, ZonedDateTime startsAt, ZonedDateTime endsAt,
                                                  int totalQuantity, int perUserLimit, Map<String, Object> couponSpec) {
        PromotionModel promotion = PromotionModel.create(name, PromotionType.COUPON_ISSUE, startsAt, endsAt);
        promotion.activate();
        promotion = promotionRepository.save(promotion);
        CouponIssuePromotionModel couponIssue = promotionRepository.saveCouponIssue(
            CouponIssuePromotionModel.create(promotion.getId(), totalQuantity, perUserLimit, couponSpec));
        promotionRepository.saveStock(CouponIssueStockModel.create(promotion.getId(), totalQuantity));
        return new CouponIssuePromotion(promotion, couponIssue);
    }

    /**
     * 발급 전 확인. 쿠폰 발급 프로모션이 있고 ACTIVE 인지 본다. 발급 TX 밖에서 잠금 없이 읽는다 (DR-53, 중지와 겹친 요청은 발급될 수 있다).
     */
    public PromotionModel getIssuable(Long promotionId) {
        PromotionModel promotion = promotionRepository.find(promotionId)
            .filter(p -> p.getType() == PromotionType.COUPON_ISSUE)
            .orElseThrow(() -> new CoreException(ErrorType.PROMOTION_NOT_FOUND, "[promotionId = " + promotionId + "] 프로모션이 없습니다."));
        if (!promotion.isActive()) {
            throw new CoreException(ErrorType.PROMOTION_NOT_ACTIVE,
                "[promotionId = " + promotionId + ", status = " + promotion.getStatus() + "] 진행 중이 아닙니다.");
        }
        return promotion;
    }

    /**
     * 선착순 쿠폰 한 장 발급. 발급 TX 는 이 두 문장만 감싼다 (DR-58). 상태 확인은 호출자가 먼저 {@link #getIssuable} 로 한다.
     * 발급 내역 insert(중복 확인)를 먼저, 모든 요청이 몰리는 수량 조건 갱신을 마지막에 해 수량 행 잠금을 commit 직전까지로 줄인다.
     * 소진이면 예외로 TX 를 rollback 해 먼저 넣은 발급 내역도 지운다.
     */
    public IssuedCouponModel issue(Long userId, Long promotionId) {
        IssuedCouponModel issued = promotionRepository.insertIssuedCoupon(IssuedCouponModel.create(userId, promotionId))
            .orElseThrow(() -> new CoreException(ErrorType.COUPON_ALREADY_ISSUED,
                "[userId = " + userId + ", promotionId = " + promotionId + "] 이미 발급받았습니다."));
        if (!promotionRepository.decreaseStock(promotionId)) {
            throw new CoreException(ErrorType.COUPON_SOLD_OUT, "[promotionId = " + promotionId + "] 쿠폰이 모두 발급되었습니다.");
        }
        return issued;
    }

    /** 생성 결과. */
    public record CouponIssuePromotion(PromotionModel promotion, CouponIssuePromotionModel couponIssue) {
    }
}
