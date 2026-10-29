package com.loopers.infrastructure.payment;

import com.loopers.application.payment.port.PaymentLookupScheduleRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional
public class PaymentLookupSchedulePersistenceAdapter implements PaymentLookupScheduleRepository {
    private final PaymentLookupScheduleJpaRepository paymentLookupScheduleJpaRepository;

    public PaymentLookupSchedulePersistenceAdapter(PaymentLookupScheduleJpaRepository paymentLookupScheduleJpaRepository) {
        this.paymentLookupScheduleJpaRepository = paymentLookupScheduleJpaRepository;
    }

    @Override
    public void save(Schedule schedule) {
        paymentLookupScheduleJpaRepository.save(new PaymentLookupScheduleJpaEntity(schedule));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Schedule> findByOrderId(long orderId) {
        return paymentLookupScheduleJpaRepository.findById(orderId).map(PaymentLookupScheduleJpaEntity::toSchedule);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Long> findDue(Instant now, int limit) {
        return paymentLookupScheduleJpaRepository.findDue(now, PageRequest.of(0, limit));
    }

    @Override
    public void delete(long orderId) {
        paymentLookupScheduleJpaRepository.deleteById(orderId);
    }
}
