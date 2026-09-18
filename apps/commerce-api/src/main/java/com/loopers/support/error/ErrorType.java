package com.loopers.support.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ErrorType implements ErrorCode {
    /** 범용 에러 */
    INTERNAL_ERROR("일시적인 오류가 발생했습니다."),
    BAD_REQUEST("잘못된 요청입니다."),
    UNAUTHENTICATED("요청자를 확인할 수 없습니다."),
    NOT_FOUND("존재하지 않는 요청입니다."),
    CONFLICT("이미 존재하는 리소스입니다.");

    private final String message;

    @Override
    public String getCode() {
        return name();
    }
}
