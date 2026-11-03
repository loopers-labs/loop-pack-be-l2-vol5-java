package com.loopers.domain.outbox;

public enum OutboxStatus {
    /** broker 성공을 아직 확인·저장하지 못했다. 수신 0회라는 뜻은 아니다. */
    PENDING,
    /** relay 가 broker 성공을 확인하고 저장했다. 소비 완료가 아니다. */
    PUBLISHED
}
