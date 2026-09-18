package com.loopers.domain.point;

import com.loopers.support.error.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum PointErrorCode implements ErrorCode {
    INVALID_CHARGE_AMOUNT("충전액은 양의 정수여야 합니다."),
    BALANCE_LIMIT_EXCEEDED("충전 후 잔액이 저장할 수 있는 범위를 넘습니다."),
    INSUFFICIENT_POINT("포인트 잔액이 부족합니다.");

    private final String message;

    @Override
    public String getCode() {
        return name();
    }
}
