package com.loopers.domain.order;

/** Outbox 행의 전달 상태 (W7 design.md 2절). {@code PUBLISHED}는 relay가 broker 확인을 저장했다는 뜻이고, 소비 완료가 아니다. */
public enum OrderOutboxStatus {
    /** 아직 broker 확인을 저장하지 못했다. relay가 다시 찾는 대상이다. */
    PENDING,
    /** relay가 broker 확인(producer future 성공)을 받고 저장했다. */
    PUBLISHED
}
