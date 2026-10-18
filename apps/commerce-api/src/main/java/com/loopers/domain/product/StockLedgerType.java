package com.loopers.domain.product;

/** TB-11 재고 원장 행의 종류 (DR-38). 원장은 append-only 라 종류는 행이 만들어질 때 정해지고 바뀌지 않는다. */
public enum StockLedgerType {
    /** 관리자가 설정한 수량과 현재 수량의 차이. 상품 생성(0 → 초기 재고)과 기존 재고 이관도 이것으로 남긴다. order_id 없음. */
    ADJUSTMENT,
    /** 주문 확정의 품목별 차감 (FR-ORDER-02). 수량은 음수, order_id 필수. */
    RESERVE,
    /** 주문 취소 시 예약분 반환. 수량은 양수, order_id 필수. 주문 취소 FR 이 아직 없어 쓰는 곳이 없다. */
    RELEASE
}
