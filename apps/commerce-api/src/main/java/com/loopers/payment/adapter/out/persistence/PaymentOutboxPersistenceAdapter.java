package com.loopers.payment.adapter.out.persistence;

import com.loopers.payment.application.port.out.PaymentOutboxPort;
import com.loopers.payment.domain.PaymentOutbox;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.util.List;

@RequiredArgsConstructor
@Component
public class PaymentOutboxPersistenceAdapter implements PaymentOutboxPort {

    private final PaymentOutboxJpaRepository outboxJpaRepository;

    @Override
    public PaymentOutbox save(PaymentOutbox outbox) {
        return outboxJpaRepository.saveAndFlush(outbox);
    }

    @Override
    public boolean claim(Long paymentId, ZonedDateTime now) {
        return false;
    }

    @Override
    public boolean close(Long paymentId, ZonedDateTime now) {
        return false;
    }

    @Override
    public void markDone(Long paymentId, ZonedDateTime now) {
    }

    @Override
    public List<Long> findPendingCreatedBefore(ZonedDateTime createdBefore, int limit) {
        return List.of();
    }

    @Override
    public List<Long> findClaimedBefore(ZonedDateTime claimedBefore, int limit) {
        return List.of();
    }
}
