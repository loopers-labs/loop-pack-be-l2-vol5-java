package com.loopers.product.application.port.in;

import java.util.Map;

/**
 * 결제 흐름이 부르는 재고 선점의 입구 (ADR-W5-02). 모두 호출자의 트랜잭션 안에서만 동작한다 — 주문·결제 상태와 함께 commit·rollback된다.
 */
public interface StockReservationUseCase {

    /**
     * 결제 요청(TX A): 상품마다 가용 재고를 줄이고 RESERVED 행을 남긴다. 하나라도 없거나 삭제됐거나 부족하면 409, 전체 rollback.
     */
    void reserve(Long orderId, Map<Long, Integer> quantitiesByProductId);

    /**
     * 결제 성공: RESERVED 행을 CONSUMED로 바꾼다. 재고는 이미 줄였으므로 바꾸지 않는다. 바뀐 행 수를 돌려준다.
     */
    int consume(Long orderId);

    /**
     * 확인된 결제 실패: RESERVED 행을 RELEASED로 바꾸고, 실제로 바뀐 행의 수량만 가용 재고에 되돌린다. 바뀐 행 수를 돌려준다.
     */
    int release(Long orderId);
}
