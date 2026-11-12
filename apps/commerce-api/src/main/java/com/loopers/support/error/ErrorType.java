package com.loopers.support.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorType {
    /** 범용 에러 */
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase(), "일시적인 오류가 발생했습니다."),
    BAD_REQUEST(HttpStatus.BAD_REQUEST, HttpStatus.BAD_REQUEST.getReasonPhrase(), "잘못된 요청입니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, HttpStatus.NOT_FOUND.getReasonPhrase(), "존재하지 않는 요청입니다."),
    CONFLICT(HttpStatus.CONFLICT, HttpStatus.CONFLICT.getReasonPhrase(), "이미 존재하는 리소스입니다."),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE.getReasonPhrase(), "일시적으로 요청을 처리할 수 없습니다. 잠시 뒤 다시 시도해 주세요."),

    /** 쿠폰 발급 */
    COUPON_SOLD_OUT(HttpStatus.CONFLICT, "SOLD_OUT", "쿠폰이 모두 소진되었습니다."),
    CAMPAIGN_INACTIVE(HttpStatus.CONFLICT, "CAMPAIGN_INACTIVE", "발급이 진행 중인 캠페인이 아닙니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
