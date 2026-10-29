package com.loopers.interfaces.consumer;

import com.loopers.application.purchase.facade.PurchaseActivityFacade;
import com.loopers.domain.purchase.enums.PurchaseRecordResult;
import com.loopers.domain.purchase.vo.OrderConfirmation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * 주문 확정 이벤트를 구매 활동으로 저장함. DB commit 이 끝난 뒤에만 ack 해 처리하지 못한 record 의 offset 이 넘어가지 않음 (6주차 설계 5).
 * 예외는 잡지 않고 error handler 로 넘김. 잡고 ack 하면 broker 에는 처리 완료로 보임
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class OrderConfirmedConsumer {

    public static final String LISTENER_ID = "order-confirmed";

    private final OrderConfirmedParser parser;
    private final PurchaseActivityFacade purchaseActivityFacade;

    @KafkaListener(
        id = LISTENER_ID,
        idIsGroup = false,
        groupId = "${order-event.consumer-group}",
        topics = "${order-event.topic}",
        containerFactory = OrderConfirmedConsumerConfig.CONTAINER_FACTORY
    )
    public void consume(ConsumerRecord<String, byte[]> record, Acknowledgment ack) {
        OrderConfirmation confirmation = parser.parse(record.key(), record.value());
        PurchaseRecordResult result = purchaseActivityFacade.record(confirmation);
        ack.acknowledge();
        log.info("주문 확정 이벤트 소비. result={} eventId={} topic={} partition={} offset={}",
            result, confirmation.eventId(), record.topic(), record.partition(), record.offset());
    }
}
