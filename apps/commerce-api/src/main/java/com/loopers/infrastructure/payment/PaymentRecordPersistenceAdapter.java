package com.loopers.infrastructure.payment;

import com.loopers.application.payment.port.PaymentRecordRepository;
import com.loopers.domain.common.Money;
import com.loopers.domain.payment.PaymentRecord;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(readOnly = true)
public class PaymentRecordPersistenceAdapter implements PaymentRecordRepository {
    private final PaymentRecordJpaRepository paymentRecordJpaRepository;

    public PaymentRecordPersistenceAdapter(PaymentRecordJpaRepository paymentRecordJpaRepository) {
        this.paymentRecordJpaRepository = paymentRecordJpaRepository;
    }

    @Override
    @Transactional
    public PaymentRecord save(PaymentRecord payment) {
        PaymentRecordJpaEntity entity;
        if (payment.getId() == null) {
            entity = new PaymentRecordJpaEntity(payment.getOrderId(), payment.getMethod().name(), payment.getAmount().value(),
                payment.getAttemptKey(), payment.getProviderId(), payment.getProviderOrderId());
        } else {
            entity = paymentRecordJpaRepository.findById(payment.getId()).orElseThrow();
        }
        entity.update(payment.getResult().name(), payment.getTransactionKey(),
            payment.getProviderStatus() == null ? null : payment.getProviderStatus().name(), payment.getProviderId());
        return toDomain(paymentRecordJpaRepository.save(entity));
    }

    @Override
    public Optional<PaymentRecord> findByOrderId(long orderId) {
        return paymentRecordJpaRepository.findByOrderId(orderId).map(this::toDomain);
    }

    @Override
    public List<PaymentRecord> findByOrderIds(Collection<Long> orderIds) {
        if (orderIds.isEmpty()) {
            return List.of();
        }
        return paymentRecordJpaRepository.findByOrderIdIn(orderIds).stream().map(this::toDomain).toList();
    }

    @Override
    public Optional<PaymentRecord> findByProviderOrderId(String providerOrderId) {
        return paymentRecordJpaRepository.findByProviderOrderId(providerOrderId).map(this::toDomain);
    }

    @Override
    public Optional<PaymentRecord> findByAttemptKey(String attemptKey) {
        return paymentRecordJpaRepository.findByAttemptKey(attemptKey).map(this::toDomain);
    }

    private PaymentRecord toDomain(PaymentRecordJpaEntity entity) {
        PaymentRecord.ProviderStatus providerStatus = entity.getProviderStatus() == null
            ? null
            : PaymentRecord.ProviderStatus.valueOf(entity.getProviderStatus());
        return PaymentRecord.restore(entity.getId(), entity.getOrderId(), PaymentRecord.Method.valueOf(entity.getMethod()),
            new Money(entity.getAmount()), PaymentRecord.Result.valueOf(entity.getResult()), entity.getAttemptKey(),
            entity.getProviderId(), entity.getProviderOrderId(), entity.getTransactionKey(), providerStatus);
    }
}
