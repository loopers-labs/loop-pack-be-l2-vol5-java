package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.PaymentDlqModel;
import com.loopers.domain.payment.PaymentDlqRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
@Component
public class PaymentDlqRepositoryImpl implements PaymentDlqRepository {

    private final PaymentDlqJpaRepository jpaRepository;

    @Override
    public PaymentDlqModel save(PaymentDlqModel dlq) {
        return jpaRepository.save(dlq);
    }

    @Override
    public List<PaymentDlqModel> findAll() {
        return jpaRepository.findAll();
    }

    @Override
    public List<PaymentDlqModel> findAllByResolved(boolean resolved) {
        return jpaRepository.findAllByResolved(resolved);
    }

    @Override
    public Optional<PaymentDlqModel> findById(Long id) {
        return jpaRepository.findById(id);
    }
}
