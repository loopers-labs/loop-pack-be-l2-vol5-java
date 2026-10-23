package com.loopers.domain.payment.repository;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import com.loopers.domain.payment.entity.Payment;

/** 주문 · 시도 키 유일 제약을 흉내 냄 */
public class FakePaymentRepository implements PaymentRepository {

    private final Map<Long, Payment> payments = new LinkedHashMap<>();
    private long sequence = 0L;
    private int findAllByOrderIdsCalls = 0;

    @Override
    public Payment save(Payment payment) {
        if (payment.getId() == null || payment.getId() == 0L) {
            boolean duplicated = payments.values().stream().anyMatch(saved ->
                Objects.equals(saved.getOrderId(), payment.getOrderId()) || saved.getAttemptKey().equals(payment.getAttemptKey()));
            if (duplicated) {
                throw new DataIntegrityViolationException("유일 제약 위반");
            }
            ReflectionTestUtils.setField(payment, "id", ++sequence);
        }
        payments.put(payment.getId(), payment);
        return payment;
    }

    @Override
    public Optional<Payment> findByOrderId(Long orderId) {
        return payments.values().stream().filter(payment -> Objects.equals(payment.getOrderId(), orderId)).findFirst();
    }

    @Override
    public Optional<Payment> findByAttemptKey(String attemptKey) {
        return payments.values().stream().filter(payment -> payment.getAttemptKey().equals(attemptKey)).findFirst();
    }

    @Override
    public Optional<Payment> findByProviderOrderId(String providerOrderId) {
        return payments.values().stream().filter(payment -> providerOrderId.equals(payment.getProviderOrderId())).findFirst();
    }

    @Override
    public List<Payment> findAllByOrderIds(Collection<Long> orderIds) {
        findAllByOrderIdsCalls++;
        return payments.values().stream().filter(payment -> orderIds.contains(payment.getOrderId())).toList();
    }

    public int findAllByOrderIdsCalls() {
        return findAllByOrderIdsCalls;
    }

    public int count() {
        return payments.size();
    }
}
