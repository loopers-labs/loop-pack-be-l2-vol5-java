package com.loopers.domain.order;

/**
 * 최초 확정 사실을 외부에 전달하는 포트. 반환은 broker 기록이나 소비 완료를 뜻하지 않는다.
 * 전달 결과는 구현이 관측하며 Kafka 타입·Future 를 호출자에게 노출하지 않는다.
 */
public interface OrderEventPublisher {

    void publish(OrderConfirmed event);
}
