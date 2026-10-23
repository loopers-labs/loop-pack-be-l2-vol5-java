package com.loopers.domain.order;

/**
 * 주문을 어떤 수단으로 결제했는지. "결제 시도 행이 없으면 POINT"처럼 없음에서 추론하지 않고
 * 확정 시점에 기록한다(docs/week5/design.md D-022). DRAFT는 아직 결제 수단이 없다.
 */
public enum PaymentMethod {
    POINT,
    PG
}
