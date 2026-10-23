package com.loopers.domain.order;

public enum ReservationStatus {
    /** 미완료 주문을 위해 가용 재고에서 수량을 빼 두었다. 주문 성공을 뜻하지 않는다. */
    RESERVED,
    /** 결제 성공으로 확보한 수량을 그대로 판매했다. 재고를 다시 줄이지 않는다. */
    CONSUMED,
    /** 결제 확정 실패로 확보한 수량을 가용 재고에 한 번 되돌렸다. */
    RELEASED
}
