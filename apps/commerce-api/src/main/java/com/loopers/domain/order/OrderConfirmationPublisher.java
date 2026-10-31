package com.loopers.domain.order;

/** 주문 확정 사실을 외부로 전달한다. 전송 기술과 결과 관찰은 구현체가 담당한다. */
public interface OrderConfirmationPublisher {
    void publish(OrderConfirmed event);
}
