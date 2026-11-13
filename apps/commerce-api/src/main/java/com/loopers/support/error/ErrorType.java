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
    /** 지금 처리할 자리가 없다. 발급 성공이나 나중 처리를 약속하지 않는다(docs/week8/decisions.md D-9). */
    TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS, HttpStatus.TOO_MANY_REQUESTS.getReasonPhrase(), "요청이 많아 지금은 처리할 수 없습니다."),
    /** 수용 상태를 확인할 수 없어 신규 요청을 받지 않는다(docs/week8/decisions.md D-2, D-9). */
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE.getReasonPhrase(), "지금은 요청을 처리할 수 없습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
