package com.loopers.application.order;

import com.loopers.domain.order.OrderConfirmed;

/**
 * 최초 PG 확정 사실을 확정 TX 안에서 기록하는 application 역할(설계 W7.2-5). 호출자의 TX 가 필수이며 전송 포트가 아니다.
 * 제품 구현은 같은 TX 에 Outbox 를 남기는 {@link OrderOutboxRecordingService} 하나다. 실패는 삼키지 않고 호출 TX 실패로 전파한다.
 */
public interface OrderConfirmedRecorder {

    void record(OrderConfirmed event);
}
