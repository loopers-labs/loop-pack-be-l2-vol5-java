package com.loopers.infrastructure.coupon;

import com.loopers.application.coupon.CouponIssueQueueScriptException;
import com.loopers.application.coupon.CouponIssueQueueUnavailableException;
import io.lettuce.core.RedisCommandExecutionException;

/**
 * Redis 예외를 대기열 예외로 바꾼다. 키 타입 오류와 대기열 Lua가 돌려준 오류는 스크립트 오류,
 * 그 밖의 연결 실패와 응답 불명은 저장소를 확인할 수 없는 상태로 본다.
 */
final class RedisCouponIssueQueueExceptionTranslator {

    static final String SCRIPT_ERROR_PREFIX = "coupon queue:";

    private RedisCouponIssueQueueExceptionTranslator() {
    }

    static RuntimeException translate(RuntimeException exception) {
        RedisCommandExecutionException commandException = findCause(exception, RedisCommandExecutionException.class);
        if (commandException == null) {
            return new CouponIssueQueueUnavailableException(exception);
        }
        String message = commandException.getMessage();
        if (message != null && (message.contains("WRONGTYPE") || message.contains(SCRIPT_ERROR_PREFIX))) {
            return new CouponIssueQueueScriptException("대기열 스크립트를 실행할 수 없습니다.", exception);
        }
        return new CouponIssueQueueUnavailableException(exception);
    }

    private static <T extends Throwable> T findCause(Throwable throwable, Class<T> type) {
        Throwable current = throwable;
        while (current != null) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            current = current.getCause();
        }
        return null;
    }
}
