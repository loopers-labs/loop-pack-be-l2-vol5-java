package com.loopers.application.order;

import com.loopers.domain.order.OrderOutboxAttempt;
import com.loopers.domain.order.OrderOutboxReceipt;

/**
 * 실제 send 성공 뒤·PUBLISHED 상태 TX 전의 통제 중단 경계(설계 W7.3-2). 제품 기본값은 아무것도 하지 않는다.
 * 테스트만 교체하며, 던진 예외는 relay 가 send·DB 실패로 분류하지 않고 그대로 전파한다.
 */
public interface OrderOutboxRelayProbe {

    OrderOutboxRelayProbe NO_OP = (attempt, receipt) -> { };

    void afterBrokerConfirmed(OrderOutboxAttempt attempt, OrderOutboxReceipt receipt);
}
