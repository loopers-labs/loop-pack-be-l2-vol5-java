package com.loopers.domain.order;

/**
 * 확정 주문 이벤트를 broker로 보내는 경계. Kafka·직렬화·timeout은 infrastructure 구현만 안다.
 *
 * <p>구현은 예외를 던지지 않고 결과를 돌려준다. 호출 시점에 주문 트랜잭션은 이미 commit됐으므로, 발행 실패가
 * 확정된 주문을 되돌리는 근거가 되면 안 된다.
 */
public interface OrderEventPublisher {

    OrderEventPublication publish(OrderConfirmedEvent event);
}
