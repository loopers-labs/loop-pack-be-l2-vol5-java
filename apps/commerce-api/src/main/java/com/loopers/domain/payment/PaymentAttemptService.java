package com.loopers.domain.payment;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Component
public class PaymentAttemptService {

    private final PaymentAttemptRepository paymentAttemptRepository;

    @Transactional(readOnly = true)
    public Optional<PaymentAttemptModel> findByOrderId(Long orderId) {
        return paymentAttemptRepository.findByOrderId(orderId);
    }

    @Transactional(readOnly = true)
    public PaymentAttemptModel getByOrderId(Long orderId) {
        return findByOrderId(orderId)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "[orderId = " + orderId + "] 결제 시도를 찾을 수 없습니다."));
    }

    @Transactional(readOnly = true)
    public PaymentAttemptModel getByProviderOrderId(String providerOrderId) {
        return paymentAttemptRepository.findByProviderOrderId(providerOrderId)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "해당 PG 주문 번호의 결제 시도를 찾을 수 없습니다."));
    }

    @Transactional(readOnly = true)
    public boolean existsByAttemptKey(String attemptKey) {
        return paymentAttemptRepository.existsByAttemptKey(attemptKey);
    }

    // 주문 목록에 결제 상태를 붙일 때 쓴다. 주문 id로 찾아 주기 쉽게 Map으로 돌려준다
    @Transactional(readOnly = true)
    public Map<Long, PaymentAttemptModel> findAllByOrderIds(Collection<Long> orderIds) {
        List<PaymentAttemptModel> attempts = paymentAttemptRepository.findAllByOrderIds(orderIds);
        return attempts.stream().collect(Collectors.toMap(PaymentAttemptModel::getOrderId, Function.identity()));
    }

    // 주문을 이미 잠근 트랜잭션 안에서 부른다. 잠금 순서: 주문 → 결제 시도 → 재고 예약 (design.md 9-5절)
    @Transactional
    public PaymentAttemptModel getByOrderIdForUpdate(Long orderId) {
        return paymentAttemptRepository.findByOrderIdForUpdate(orderId)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "[orderId = " + orderId + "] 결제 시도를 찾을 수 없습니다."));
    }

    @Transactional
    public PaymentAttemptModel create(Long orderId, Long userId, String attemptKey, String cardType, Long amount) {
        return paymentAttemptRepository.save(new PaymentAttemptModel(orderId, userId, attemptKey, cardType, amount));
    }
}
