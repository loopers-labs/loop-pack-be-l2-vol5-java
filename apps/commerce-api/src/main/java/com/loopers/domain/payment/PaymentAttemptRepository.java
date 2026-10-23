package com.loopers.domain.payment;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PaymentAttemptRepository {
    PaymentAttemptModel save(PaymentAttemptModel attempt);

    Optional<PaymentAttemptModel> findByOrderId(Long orderId);

    // 주문을 잠근 뒤에 잠근다 (design.md 9-5절 잠금 순서)
    Optional<PaymentAttemptModel> findByOrderIdForUpdate(Long orderId);

    // callback이 알려 준 PG 주문 번호로 시도를 찾는다. 잠금 없이 읽는 용도다
    Optional<PaymentAttemptModel> findByProviderOrderId(String providerOrderId);

    boolean existsByAttemptKey(String attemptKey);

    // 주문 목록·관리자 목록에 결제 상태를 붙일 때 주문마다 조회하지 않도록 한 번에 읽는다
    List<PaymentAttemptModel> findAllByOrderIds(Collection<Long> orderIds);
}
