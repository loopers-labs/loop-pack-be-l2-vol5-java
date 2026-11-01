package com.loopers.interfaces.consumer;

import com.loopers.application.purchase.PurchaseActivityFacade;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/** 주문 확정 이벤트를 구매 활동으로 저장한다. Facade 가 DB commit 을 끝낸 뒤에만 ack 하고, 예외는 error handler 로 보낸다. */
@RequiredArgsConstructor
@Component
public class OrderConfirmedConsumer {

    public static final String LISTENER_ID = "order-confirmed-consumer";

    private final OrderConfirmedMessageParser parser;
    private final PurchaseActivityFacade purchaseActivityFacade;

    @KafkaListener(
        id = LISTENER_ID,
        topics = "${order-event.topic}",
        groupId = "${order-event.consumer-group}",
        containerFactory = OrderActivityKafkaConfig.ORDER_ACTIVITY_LISTENER
    )
    public void listen(ConsumerRecord<String, byte[]> record, Acknowledgment acknowledgment) {
        purchaseActivityFacade.save(parser.parse(record));
        acknowledgment.acknowledge();
    }
}
