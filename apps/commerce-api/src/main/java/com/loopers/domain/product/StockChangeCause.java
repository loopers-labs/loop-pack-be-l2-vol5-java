package com.loopers.domain.product;

/**
 * 재고 수량이 변경된 원인. ORDER_DEDUCTION 은 과거 포인트 확정의 차감이며 보존한다.
 * PG 결제는 결제 시작 때 ORDER_RESERVATION, 실패 확정 때 ORDER_RELEASE 를 쓰고 성공에는 이력을 남기지 않는다.
 */
public enum StockChangeCause {
    ADMIN_CHANGE,
    ORDER_DEDUCTION,
    ORDER_RESERVATION,
    ORDER_RELEASE
}
