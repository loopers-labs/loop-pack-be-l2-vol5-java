package com.loopers.domain.order;

/** 저장된 메시지를 전송하고 broker 확인까지 기다린다. 확인하지 못하면 예외를 던진다. */
public interface OrderConfirmationPublisher {
    void publish(OrderOutboxDelivery delivery);
}
