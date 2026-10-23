package com.loopers.domain.order;

/** 확정 주문의 결제 방식. 과거 포인트 확정은 POINT, 신규 결제는 PG 다. DRAFT 는 null 이다. */
public enum PaymentMethod {
    POINT,
    PG
}
