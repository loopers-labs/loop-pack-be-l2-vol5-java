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
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, HttpStatus.UNAUTHORIZED.getReasonPhrase(), "사용자 식별에 실패했습니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, HttpStatus.NOT_FOUND.getReasonPhrase(), "존재하지 않는 요청입니다."),
    CONFLICT(HttpStatus.CONFLICT, HttpStatus.CONFLICT.getReasonPhrase(), "이미 존재하는 리소스입니다."),
    /** 처리 자리가 없다. 재시도하면 받을 수 있다 (W8 §8-2) */
    TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS, HttpStatus.TOO_MANY_REQUESTS.getReasonPhrase(), "처리 가능한 자리가 없습니다. 잠시 후 다시 시도해 주세요."),
    /** 서버 쪽 판단 근거가 불확실해 요청을 받지 않는다. 재시도하면 받을 수 있다 (W8 §7-1) */
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE.getReasonPhrase(), "일시적으로 요청을 처리할 수 없습니다. 잠시 후 다시 시도해 주세요.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
