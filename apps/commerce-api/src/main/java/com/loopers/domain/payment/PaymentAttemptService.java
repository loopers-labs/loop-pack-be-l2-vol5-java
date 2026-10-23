package com.loopers.domain.payment;

import com.loopers.domain.common.Money;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

@RequiredArgsConstructor
@Component
public class PaymentAttemptService {

    private final PaymentAttemptRepository paymentAttemptRepository;

    /** 발송을 시작한다고 기록하는 시도를 만들어 저장한다. 주문·시도 키·providerOrderId 의 유일 제약이 중복을 막는다. */
    public PaymentAttempt request(Long orderId, String attemptKey, Money amount) {
        return paymentAttemptRepository.save(PaymentAttempt.request(orderId, attemptKey, amount));
    }

    public Optional<PaymentAttempt> findByOrderId(Long orderId) {
        return paymentAttemptRepository.findByOrderId(orderId);
    }

    public Optional<PaymentAttempt> findByAttemptKey(String attemptKey) {
        return paymentAttemptRepository.findByAttemptKey(attemptKey);
    }

    public Optional<PaymentAttempt> findByProviderOrderId(String providerOrderId) {
        return paymentAttemptRepository.findByProviderOrderId(providerOrderId);
    }

    /**
     * 결제를 시작한 주문의 시도를 조회한다. 결제 대기 주문에 시도가 없는 것은 호출하는 쪽이 아니라 데이터의 문제라
     * {@code INTERNAL_ERROR} 다.
     */
    public PaymentAttempt getByOrderId(Long orderId) {
        return findByOrderId(orderId).orElseThrow(() -> new CoreException(
            ErrorType.INTERNAL_ERROR, "결제 시도를 찾을 수 없습니다. (주문 ID: " + orderId + ")"
        ));
    }
}
