package com.loopers.domain.payment;

import java.util.List;
import java.util.Optional;

public interface PaymentDlqRepository {
    PaymentDlqModel save(PaymentDlqModel dlq);

    List<PaymentDlqModel> findAll();

    List<PaymentDlqModel> findAllByResolved(boolean resolved);

    Optional<PaymentDlqModel> findById(Long id);
}
