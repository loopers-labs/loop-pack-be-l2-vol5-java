package com.loopers.domain.coupon.enums;

import com.loopers.support.error.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum CouponErrorCode implements ErrorCode {
    CAMPAIGN_NOT_FOUND("존재하지 않는 쿠폰 캠페인입니다."),
    CAMPAIGN_NOT_ACTIVE("발급 중인 캠페인이 아닙니다."),
    /** 실패가 아니라 지금 확인 · 처리할 수 없다는 뜻. 발급 약속이 아니다 (8주차 D-139, D-146) */
    COUPON_ISSUE_UNAVAILABLE("지금은 쿠폰 발급을 처리하거나 확인할 수 없습니다. 잠시 후 다시 확인해 주세요."),
    INVALID_CAMPAIGN_NAME("캠페인 이름은 공백뿐일 수 없고 50자 이하여야 합니다."),
    INVALID_TOTAL_QUOTA("총 발급 한도는 1 이상이어야 합니다."),
    INVALID_DISCOUNT("정액 할인은 1원 이상, 정률 할인은 1% 이상 100% 이하여야 합니다."),
    INVALID_COUPON_DESCRIPTION("쿠폰 설명은 공백뿐일 수 없고 100자 이하여야 합니다.");

    private final String message;

    @Override
    public String getCode() {
        return name();
    }
}
