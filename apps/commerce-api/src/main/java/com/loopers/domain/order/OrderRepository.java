package com.loopers.domain.order;

import java.util.List;
import java.util.Optional;

public interface OrderRepository {
    Order save(Order order);

    Optional<Order> findById(Long id);

    List<Order> findAllByUserId(Long userId);

    /**
     * 관리자 조회용. userId 가 null 이면 전체 주문을 최신순으로 반환한다.
     */
    List<Order> findAllForAdmin(Long userId, int page, int size);

    /**
     * 주문과 품목을 한 번에 읽는다. 트랜잭션 밖에서 응답을 만들 때 지연 로딩에 걸리지 않도록 품목까지 fetch 한다.
     */
    Optional<Order> findByIdWithItems(Long orderId);

    /**
     * TX A ①. 주문 행을 비관적 락(SELECT … FOR UPDATE)으로 읽는다.
     * 소유권 → 기존 시도 → DRAFT 순 판정을 한 WHERE 로 정할 수 없어 잠근 뒤 최신 커밋 값을 읽는다.
     */
    Optional<Order> findByIdForUpdate(Long orderId);

    /**
     * TX B ⑦. WHERE status = 'PAYMENT_PENDING' 일 때만 CONFIRMED(+paidAmount) 또는 PAYMENT_FAILED 로 바꾼다.
     *
     * @return 갱신된 행 수. 0 이면 이미 종결된 주문이다.
     */
    int finishIfPaymentPending(Long orderId, Order.OrderStatus status, long paidAmount);
}
