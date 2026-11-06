package com.loopers.domain.payment;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@RequiredArgsConstructor
@Component
public class PaymentDlqService {

    private final PaymentDlqRepository paymentDlqRepository;

    @Transactional
    public PaymentDlqModel enqueue(Long orderId, String reason, PaymentAttemptStatus attemptStatus) {
        return paymentDlqRepository.save(new PaymentDlqModel(orderId, reason, attemptStatus));
    }

    @Transactional(readOnly = true)
    public List<PaymentDlqModel> findAll() {
        return paymentDlqRepository.findAll();
    }

    @Transactional(readOnly = true)
    public List<PaymentDlqModel> findUnresolved() {
        return paymentDlqRepository.findAllByResolved(false);
    }

    @Transactional(readOnly = true)
    public PaymentDlqModel getById(Long id) {
        return paymentDlqRepository.findById(id)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "[id = " + id + "] DLQ 항목을 찾을 수 없습니다."));
    }

    @Transactional
    public PaymentDlqModel resolve(Long id, String note) {
        PaymentDlqModel dlq = getById(id);
        dlq.resolve(note);
        return paymentDlqRepository.save(dlq);
    }
}
