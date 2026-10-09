package com.loopers.support.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 도메인에서 던지는 에러의 종류.
 *
 * <p>전송 계층(HTTP 등)에 대한 지식을 갖지 않는다. HTTP 상태 코드로의 변환은
 * {@code interfaces.api} 레이어의 책임이다.
 */
@Getter
@RequiredArgsConstructor
public enum ErrorType {
    /** 범용 에러 */
    INTERNAL_ERROR("일시적인 오류가 발생했습니다."),
    INVALID_INPUT("잘못된 요청입니다."),
    NOT_FOUND("존재하지 않는 요청입니다."),
    CONFLICT("이미 존재하는 리소스입니다.");

    private final String message;

    /** 클라이언트에 노출되는 안정적인 에러 식별자. */
    public String getCode() {
        return name();
    }
}
