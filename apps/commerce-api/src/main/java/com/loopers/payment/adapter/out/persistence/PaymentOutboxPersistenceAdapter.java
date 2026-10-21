package com.loopers.payment.adapter.out.persistence;

import com.loopers.payment.application.port.out.PaymentOutboxPort;
import com.loopers.payment.domain.PaymentOutbox;
import com.loopers.payment.domain.PaymentOutboxStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
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
        return outboxJpaRepository.claim(paymentId, PaymentOutboxStatus.PENDING, PaymentOutboxStatus.CLAIMED, now) == 1;
    }

    @Override
    public boolean close(Long paymentId, ZonedDateTime now) {
        return outboxJpaRepository.complete(paymentId, PaymentOutboxStatus.PENDING, PaymentOutboxStatus.CLOSED, now) == 1;
    }

    @Override
    public void markDone(Long paymentId, ZonedDateTime now) {
        outboxJpaRepository.complete(paymentId, PaymentOutboxStatus.CLAIMED, PaymentOutboxStatus.DONE, now);
    }

    @Override
    public List<Long> findPendingCreatedBefore(ZonedDateTime createdBefore, int limit) {
        return outboxJpaRepository.findPaymentIdsCreatedBefore(PaymentOutboxStatus.PENDING, createdBefore, PageRequest.of(0, limit));
    }

    @Override
    public List<Long> findClaimedBefore(ZonedDateTime claimedBefore, int limit) {
        return outboxJpaRepository.findPaymentIdsClaimedBefore(PaymentOutboxStatus.CLAIMED, claimedBefore, PageRequest.of(0, limit));
    }
}
