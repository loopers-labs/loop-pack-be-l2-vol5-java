package com.loopers.domain.payment;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
@Component
public class PaymentAttemptService {

    private final PaymentAttemptRepository paymentAttemptRepository;

    @Transactional
    public PaymentAttemptModel create(Long orderId, String paymentAttemptKey, String providerOrderId, long amount) {
        return paymentAttemptRepository.save(
            new PaymentAttemptModel(orderId, paymentAttemptKey, providerOrderId, amount)
        );
    }

    @Transactional(readOnly = true)
    public Optional<PaymentAttemptModel> findByOrderId(Long orderId) {
        return paymentAttemptRepository.findByOrderId(orderId);
    }

    @Transactional(readOnly = true)
    public Optional<PaymentAttemptModel> findByPaymentAttemptKey(String key) {
        return paymentAttemptRepository.findByPaymentAttemptKey(key);
    }

    @Transactional
    public PaymentAttemptModel save(PaymentAttemptModel attempt) {
        return paymentAttemptRepository.save(attempt);
    }

    @Transactional(readOnly = true)
    public List<PaymentAttemptModel> findPendingBefore(ZonedDateTime sentBefore) {
        return paymentAttemptRepository.findPendingBefore(sentBefore);
    }

    @Transactional(readOnly = true)
    public List<PaymentAttemptModel> findUnknownBefore(ZonedDateTime sentBefore) {
        return paymentAttemptRepository.findUnknownBefore(sentBefore);
    }

    @Transactional(readOnly = true)
    public PaymentAttemptModel getByOrderId(Long orderId) {
        return paymentAttemptRepository.findByOrderId(orderId)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "[orderId = " + orderId + "] 결제 시도를 찾을 수 없습니다."));
    }
}
