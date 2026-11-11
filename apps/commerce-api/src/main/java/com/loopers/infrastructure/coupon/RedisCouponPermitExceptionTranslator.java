package com.loopers.infrastructure.coupon;

import com.loopers.application.coupon.CouponPermitScriptException;
import com.loopers.application.coupon.CouponPermitStoreUnavailableException;
import io.lettuce.core.RedisCommandExecutionException;

final class RedisCouponPermitExceptionTranslator {

    private RedisCouponPermitExceptionTranslator() {}

    static RuntimeException translate(RuntimeException exception) {
        RedisCommandExecutionException commandException = findCause(exception, RedisCommandExecutionException.class);
        if (commandException == null) {
            return new CouponPermitStoreUnavailableException(exception);
        }

        String message = commandException.getMessage();
        if (message != null && (message.contains("WRONGTYPE") || message.contains("invalid admission input"))) {
            return new CouponPermitScriptException(exception);
        }
        return new CouponPermitStoreUnavailableException(exception);
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
