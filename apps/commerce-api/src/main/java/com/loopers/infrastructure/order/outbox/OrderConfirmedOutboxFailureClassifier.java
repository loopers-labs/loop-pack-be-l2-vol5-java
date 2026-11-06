package com.loopers.infrastructure.order.outbox;

import org.apache.kafka.common.errors.InterruptException;
import org.apache.kafka.common.errors.RetriableException;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeoutException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

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
        Throwable cause = rootCause(failure);
        String detail = cause.getMessage() == null ? "" : cause.getMessage().replaceAll("[\\r\\n]+", " ");
        String message = cause.getClass().getSimpleName() + (detail.isBlank() ? "" : ": " + detail);
        return message.length() <= 500 ? message : message.substring(0, 500);
    }

    public String reason(Throwable failure) {
        return rootCause(failure).getClass().getSimpleName();
    }

    private Throwable rootCause(Throwable failure) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = failure;
        visited.add(current);
        while (current.getCause() != null && visited.add(current.getCause())) {
            current = current.getCause();
        }
        return current;
    }

    private boolean hasCause(Throwable failure, Class<? extends Throwable> type) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = failure;
        while (current != null && visited.add(current)) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    public enum FailureKind { RETRYABLE, BLOCKED, INTERRUPTED }
}
