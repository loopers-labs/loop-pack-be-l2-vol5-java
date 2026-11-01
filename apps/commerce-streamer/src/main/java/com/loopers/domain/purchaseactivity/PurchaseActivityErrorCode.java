package com.loopers.domain.purchaseactivity;

/**
 * 구매 활동 소비의 실패 분류(소비 설계 3·4절). 어느 것도 구매 효과를 만들지 않고 처리 실패로 전파한다.
 * DB 기술 오류는 여기로 바꾸지 않는다.
 */
public enum PurchaseActivityErrorCode {
    /** wire 형식·타입·범위·버전 위반. */
    INVALID_EVENT,
    /** 정상 스키마의 key·eventId·orderId 관계 불일치, 또는 저장된 사건과 식별자가 다름. */
    IDENTITY_CONFLICT,
    /** 정상 저장 사건과 식별자는 같지만 업무 사실이 다름. */
    FACT_CONFLICT,
    /** 저장된 완료·활동 자체가 계약을 지키지 않음. 입력 충돌로 분류하지 않는다. */
    STORAGE_INTEGRITY_ERROR
}
