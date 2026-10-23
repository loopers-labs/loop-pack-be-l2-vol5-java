package com.loopers.domain.order;

/**
 * 주문 상태. 주문은 DRAFT 로 만들어지고, PG 결제를 시작하면 PAYMENT_PENDING 을 거쳐 CONFIRMED 또는 PAYMENT_FAILED 로 끝난다.
 *
 * <p>CONFIRMED 와 PAYMENT_FAILED 는 종결 상태라 나가는 전이가 없다. 포인트로 결제한 과거 주문은 CONFIRMED 다.
 */
public enum OrderStatus {
    /** 결제 전. 재고를 확보하지 않은 상태 */
    DRAFT,
    /** 결제를 시작해 재고를 확보했고 PG 결과를 기다리는 상태. 결과 미확인(UNKNOWN)도 여기에 머문다 */
    PAYMENT_PENDING,
    /** 결제를 마친 상태 */
    CONFIRMED,
    /** PG 결제가 확정 실패해 재고를 돌려준 상태 */
    PAYMENT_FAILED
}
