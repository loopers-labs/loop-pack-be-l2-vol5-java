package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.CallbackInboxStatus;
import com.loopers.domain.payment.PaymentCallbackInbox;
import com.loopers.domain.payment.PaymentCallbackInboxRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;

@RequiredArgsConstructor
@Component
public class PaymentCallbackInboxRepositoryImpl implements PaymentCallbackInboxRepository {

    private static final List<CallbackInboxStatus> DUE_STATUSES = List.of(CallbackInboxStatus.RECEIVED, CallbackInboxStatus.RETRY);

    private final PaymentCallbackInboxJpaRepository paymentCallbackInboxJpaRepository;

    @Override
    public PaymentCallbackInbox save(PaymentCallbackInbox inbox) {
        return paymentCallbackInboxJpaRepository.save(inbox);
    }

    @Override
    public List<PaymentCallbackInbox> findDue(ZonedDateTime now, int limit) {
        return paymentCallbackInboxJpaRepository.findDue(DUE_STATUSES, now, PageRequest.of(0, limit));
    }

    @Override
    public List<PaymentCallbackInbox> findAllByIdIn(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return paymentCallbackInboxJpaRepository.findAllByIdIn(ids);
    }
}
