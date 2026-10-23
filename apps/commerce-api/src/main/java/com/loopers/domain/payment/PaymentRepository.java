package com.loopers.domain.payment;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 결제 시도 저장 포트(설계 2.7). 반환 Entity 는 호출 TX 안에서만 쓰고, 결제·충돌 요약을 숨기는 삭제 필터는 두지 않는다.
 * 일반 조회만 제공한다. 같은 주문의 직렬화는 호출자가 먼저 잡은 Order PK 잠금이 맡는다.
 */
public interface PaymentRepository {

    /** 업무 TX 에서 Order 잠금 뒤 처음 하는 일반 조회. */
    Optional<PaymentModel> findByOrderId(Long orderId);

    boolean existsByAttemptKey(String attemptKey);

    /** 콜백 후보 식별용. Entity·상태 없이 orderId 만, 중복을 숨기지 않고 돌려준다. */
    List<Long> findOrderIdsByProviderOrderId(String provider, String providerAccount, String providerOrderId);

    List<PaymentModel> findAllByOrderIds(Collection<Long> orderIds);

    PaymentModel save(PaymentModel payment);
}
