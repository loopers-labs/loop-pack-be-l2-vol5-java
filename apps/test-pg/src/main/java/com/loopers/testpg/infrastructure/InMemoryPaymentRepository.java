package com.loopers.testpg.infrastructure;

import com.loopers.testpg.domain.Payment;
import com.loopers.testpg.domain.PaymentRepository;
import com.loopers.testpg.domain.PaymentStatus;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

@Repository
public class InMemoryPaymentRepository implements PaymentRepository {
    private final ConcurrentHashMap<String, Payment> payments = new ConcurrentHashMap<>();

    @Override
    public void save(Payment payment) {
        if (payments.putIfAbsent(payment.getTransactionKey(), payment) != null) {
            throw new IllegalStateException("이미 존재하는 거래 키입니다.");
        }
    }

    @Override
    public Optional<Payment> findByTransactionKey(String transactionKey) {
        return Optional.ofNullable(payments.get(transactionKey));
    }

    @Override
    public List<Payment> findAllByOrderId(String orderId) {
        return payments.values().stream()
            .filter(payment -> payment.getOrderId().equals(orderId))
            .sorted(Comparator.comparing(Payment::getTransactionKey))
            .toList();
    }

    @Override
    public Optional<Payment> completeIfPending(String transactionKey, PaymentStatus result) {
        if (result != PaymentStatus.SUCCESS && result != PaymentStatus.FAILED) {
            throw new IllegalArgumentException("종결 결과로만 처리할 수 있습니다: " + result);
        }
        AtomicReference<Payment> completed = new AtomicReference<>();
        payments.computeIfPresent(transactionKey, (key, payment) -> {
            if (payment.getStatus() != PaymentStatus.PENDING) {
                return payment;
            }
            Payment done = result == PaymentStatus.SUCCESS ? payment.succeed() : payment.fail();
            completed.set(done);
            return done;
        });
        return Optional.ofNullable(completed.get());
    }
}
