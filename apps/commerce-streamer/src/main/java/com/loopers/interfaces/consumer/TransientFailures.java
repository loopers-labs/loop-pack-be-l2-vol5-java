package com.loopers.interfaces.consumer;

import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.CannotCreateTransactionException;

import java.util.List;

final class TransientFailures {

    private static final List<Class<? extends Throwable>> RETRYABLE = List.of(
        PessimisticLockingFailureException.class,
        QueryTimeoutException.class,
        CannotCreateTransactionException.class
    );

    private TransientFailures() {
    }

    static boolean isTransient(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            for (Class<? extends Throwable> retryable : RETRYABLE) {
                if (retryable.isInstance(cause)) {
                    return true;
                }
            }
        }
        return false;
    }
}
