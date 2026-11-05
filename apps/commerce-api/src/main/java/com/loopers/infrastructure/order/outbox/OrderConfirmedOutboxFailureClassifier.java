package com.loopers.infrastructure.order.outbox;

import org.apache.kafka.common.errors.InterruptException;
import org.apache.kafka.common.errors.RetriableException;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeoutException;

@Component
public class OrderConfirmedOutboxFailureClassifier {

    public FailureKind classify(Throwable failure) {
        if (hasCause(failure, InterruptedException.class) || hasCause(failure, InterruptException.class)) {
            return FailureKind.INTERRUPTED;
        }
        if (hasCause(failure, TimeoutException.class) || hasCause(failure, RetriableException.class)) {
            return FailureKind.RETRYABLE;
        }
        return FailureKind.BLOCKED;
    }

    public String safeMessage(Throwable failure) {
        String detail = failure.getMessage() == null ? "" : failure.getMessage().replaceAll("[\\r\\n]+", " ");
        String message = failure.getClass().getSimpleName() + (detail.isBlank() ? "" : ": " + detail);
        return message.length() <= 500 ? message : message.substring(0, 500);
    }

    private boolean hasCause(Throwable failure, Class<? extends Throwable> type) {
        Throwable current = failure;
        while (current != null) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    public enum FailureKind { RETRYABLE, BLOCKED, INTERRUPTED }
}
