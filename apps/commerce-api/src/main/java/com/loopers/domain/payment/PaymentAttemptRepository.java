package com.loopers.domain.payment;

import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PaymentAttemptRepository {

    PaymentAttempt save(PaymentAttempt attempt);

    Optional<PaymentAttempt> findByOrderId(Long orderId);

    /**
     * 비관적 락으로 조회한다 — 결과 반영(TX B)이 겹칠 때 전이를 한 번만 일으키기 위해서다(INV-002).
     * 락 순서는 주문 → 결제 시도 → 상품이므로, 호출자는 주문을 먼저 잠근 뒤 이 메서드를 부른다(docs/week5/design.md D-002).
     */
    Optional<PaymentAttempt> findByOrderIdForUpdate(Long orderId);

    Optional<PaymentAttempt> findByAttemptKey(String attemptKey);

    /** 주문 목록에 결제 정보를 붙일 때 한 번에 읽는다(주문마다 따로 읽지 않는다). */
    List<PaymentAttempt> findAllByOrderIdIn(Collection<Long> orderIds);

    /**
     * 회복 작업 대상 — 다음 확인 시각이 지난 시도 중 미종결이거나 관리자 실패 처리 뒤 감시 중인 것(D-013)을
     * 발송 시작이 오래된 순으로(D-034, D-041). 잠그지 않는다.
     */
    List<PaymentAttempt> findDueForCheck(ZonedDateTime now, int limit);

    /**
     * 잠그지 않고 읽는다 — callback 워커가 주문 번호(orderId)를 알아낼 때 쓴다. orderId는 생성 뒤 바뀌지 않으므로
     * 잠그지 않아도 안전하다(INV-007). 상태는 주문·시도를 잠근 뒤 다시 읽어 판단한다.
     */
    Optional<PaymentAttempt> findByProviderOrderId(String providerOrderId);

    /**
     * findByProviderOrderId와 같은 이유로 잠그지 않는다.
     */
    Optional<PaymentAttempt> findByTransactionKey(String transactionKey);
}
