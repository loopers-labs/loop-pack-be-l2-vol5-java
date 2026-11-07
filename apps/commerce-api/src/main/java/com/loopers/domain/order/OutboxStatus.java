package com.loopers.domain.order;

/** 주문 이벤트 Outbox 행의 전송 상태 (TB-14). PUBLISHED 는 broker 기록 확인까지다. 소비 완료가 아니다. */
public enum OutboxStatus {
    /** 아직 broker 기록을 확인하지 못했다. 재시도 시각이 되면 relay 가 보낸다. */
    PENDING,
    /** broker 가 기록을 확인했고 그 사실을 저장했다. 다시 보내지 않는다. */
    PUBLISHED,
    /** 전송 실패가 연속 상한에 닿았다. relay 가 자동으로 고르지 않는다. eventId 를 지정한 재개로만 PENDING 이 된다. */
    BLOCKED
}
