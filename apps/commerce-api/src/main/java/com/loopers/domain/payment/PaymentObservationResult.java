package com.loopers.domain.payment;

public enum PaymentObservationResult {
    /** 메모리의 시도 상태가 변경됨. DB 커밋이나 주문·재고 반영 완료를 뜻하지 않는다. */
    APPLIED,
    /** 기존 사실을 유지함. 추가 효과를 만들어서는 안 된다. */
    UNCHANGED,
    /** 식별·금액·종결 결과 충돌. 신규 HTTP 오류 계약을 정하는 값은 아니다. */
    CONFLICT
}
