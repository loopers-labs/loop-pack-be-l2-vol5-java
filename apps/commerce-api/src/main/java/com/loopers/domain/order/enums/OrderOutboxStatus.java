package com.loopers.domain.order.enums;

/** PUBLISHED 는 broker 확인을 저장했다는 뜻이지 소비 완료가 아님. BLOCKED 는 사람이 재개할 미해결 작업 (7주차 설계 4.3, 4.4) */
public enum OrderOutboxStatus {
    PENDING,
    PUBLISHED,
    BLOCKED
}
