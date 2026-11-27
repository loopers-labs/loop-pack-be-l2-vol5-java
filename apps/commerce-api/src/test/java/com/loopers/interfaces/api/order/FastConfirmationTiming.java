package com.loopers.interfaces.api.order;

import com.loopers.application.order.PaymentConfirmationDeadline;
import com.loopers.application.order.PaymentConfirmationTiming;
import com.loopers.infrastructure.payment.support.LocalTestPg;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/** Advances the test clock and can exhaust the initial lookup budget after a selected order's PG POST. */
final class FastConfirmationTiming implements PaymentConfirmationTiming {
    private final LocalTestPg pg;
    private long elapsedNanos;
    private Runnable afterSleep = () -> { };
    private final Map<String, String> heldRequests = new ConcurrentHashMap<>();

    FastConfirmationTiming(LocalTestPg pg) {
        this.pg = pg;
    }

    void expireAfterPgCreate(String confirmPath, String providerOrderId) {
        heldRequests.put(confirmPath, providerOrderId);
    }

    void allowLookups() {
        heldRequests.clear();
    }

    boolean areLookupsAllowed() {
        return heldRequests.isEmpty();
    }

    @Override
    public long nanoTime() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes servlet) {
            String providerOrderId = heldRequests.get(servlet.getRequest().getRequestURI());
            if (providerOrderId != null && pg.requests().stream()
                .anyMatch(call -> "POST".equals(call.method()) && providerOrderId.equals(call.orderId()))) {
                return elapsedNanos + PaymentConfirmationDeadline.EXTERNAL_CONFIRMATION_BUDGET.toNanos();
            }
        }
        return elapsedNanos;
    }

    @Override
    public void sleep(Duration duration) {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(duration.isNegative()).isFalse();
        elapsedNanos += duration.toNanos();
        afterSleep.run();
    }

    void afterSleep(Runnable action) {
        afterSleep = action;
    }

    @Override
    public double jitterFactor() {
        return 1.0;
    }

    void reset() {
        elapsedNanos = 0;
        afterSleep = () -> { };
        allowLookups();
    }

}
