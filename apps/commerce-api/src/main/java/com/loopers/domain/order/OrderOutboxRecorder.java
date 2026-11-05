package com.loopers.domain.order;

/** 주문 확정 사실을 호출한 TX 안에 전달 대상으로 남긴다. 별도 TX로 나누면 확정과 Outbox 중 한쪽만 남을 수 있다. */
public interface OrderOutboxRecorder {
    void record(OrderConfirmed event);

    /** 복구용. 행이 없으면 추가하고 true를 돌려준다. 같은 eventId의 행이 다른 내용이면 추측하지 않고 예외를 던진다. */
    boolean recordIfAbsent(OrderConfirmed event);
}
