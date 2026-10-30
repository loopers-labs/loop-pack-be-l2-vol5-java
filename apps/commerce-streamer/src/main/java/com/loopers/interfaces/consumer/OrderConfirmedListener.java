package com.loopers.interfaces.consumer;

import com.loopers.application.purchase.PurchaseActivityFacade;
import com.loopers.domain.purchase.RecordAddress;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * OrderConfirmed 기록 하나를 받아 구매 활동 처리에 넘긴다(docs/week6/design.md 3번 소비).
 *
 * <p>ack는 처리가 정상으로 끝났을 때만 부른다(INV-606). MANUAL_IMMEDIATE에서 ack는 우리 코드가 부르고 Kafka는 그걸 그대로 믿는다 —
 * 어느 실패에서 ack를 부르느냐가 곧 설계다(docs/week6/decisions.md D-8). 그래서 여기서는 예외를 잡지 않는다. 다시 해도 안 풀리는
 * 실패(conflict·계약 깨짐)는 facade가 기록하고 정상 반환하고, 그 밖의 예외는 그대로 나가 container를 멈춘다(D-20, D-29).
 *
 * <p>id는 테스트와 운영자가 KafkaListenerEndpointRegistry에서 이 container를 찾아 재시작하는 이름이다(D-20).
 */
@RequiredArgsConstructor
@Component
public class OrderConfirmedListener {

    public static final String LISTENER_ID = "order-confirmed-purchase-activity";

    private final PurchaseActivityFacade purchaseActivityFacade;

    @KafkaListener(
        id = LISTENER_ID,
        topics = "${events.order-confirmed.topic}",
        groupId = "${events.order-confirmed.group-id}",
        containerFactory = OrderConfirmedListenerConfig.FACTORY
    )
    public void listen(ConsumerRecord<String, byte[]> record, Acknowledgment acknowledgment) {
        purchaseActivityFacade.handle(
            new RecordAddress(record.topic(), record.partition(), record.offset()), record.key(), record.value());
        acknowledgment.acknowledge();
    }
}
