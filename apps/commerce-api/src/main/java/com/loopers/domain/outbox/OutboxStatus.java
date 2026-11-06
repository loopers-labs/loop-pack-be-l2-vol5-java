package com.loopers.domain.outbox;

public enum OutboxStatus {
    /** broker 성공을 아직 확인·저장하지 못했다. 수신 0회라는 뜻은 아니다. */
    PENDING,
    /** relay 가 broker 성공을 확인하고 저장했다. 소비 완료가 아니다. */
    PUBLISHED,
    /** 다시 보내도 거절될 메시지라 격리했다. 사람이 원인을 고친 뒤 PENDING 으로 되살린다. */
    BLOCKED
}
