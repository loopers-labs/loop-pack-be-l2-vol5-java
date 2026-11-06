package com.loopers.domain.order;

/**
 * 확정 이벤트를 Outbox에 저장할 값(JSON)으로 바꾸는 경계. 직렬화 방식은 infrastructure 구현만 안다.
 *
 * <p>실패하면 예외를 던진다. 주문 확정 트랜잭션 안에서 부르므로, 직렬화 실패는 확정 전체의 rollback이 된다.
 */
public interface OrderEventSerializer {

    String serialize(OrderConfirmedEvent event);
}
