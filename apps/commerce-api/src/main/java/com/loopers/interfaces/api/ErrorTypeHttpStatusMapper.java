package com.loopers.interfaces.api;

import com.loopers.support.error.ErrorType;
import org.springframework.http.HttpStatus;

/**
 * 도메인 에러 종류를 HTTP 상태 코드로 변환한다.
 *
 * <p>전송 계층에 대한 지식을 이 클래스 한 곳에 모아두기 위해 존재한다.
 * switch 에 의도적으로 {@code default} 절을 두지 않으므로, {@link ErrorType} 에
 * 상수가 추가되면 이 곳에서 컴파일 에러가 발생한다.
 */
public final class ErrorTypeHttpStatusMapper {

    private ErrorTypeHttpStatusMapper() {
    }

    public static HttpStatus toHttpStatus(ErrorType errorType) {
        return switch (errorType) {
            case INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
            case INVALID_INPUT -> HttpStatus.BAD_REQUEST;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
        };
    }
}
