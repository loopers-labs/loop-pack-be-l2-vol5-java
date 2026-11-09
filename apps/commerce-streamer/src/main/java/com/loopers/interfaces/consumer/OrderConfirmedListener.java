package com.loopers.interfaces.consumer;

import com.loopers.application.purchase.PurchaseActivityHandler;
import com.loopers.application.purchase.PurchaseActivityRecorder;
import com.loopers.config.kafka.OrderActivityKafkaConfig;
import com.loopers.domain.purchase.OrderConfirmation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * {@code order-events}의 확정 주문 이벤트를 구매 활동으로 반영한다.
 *
 * <p>순서는 파싱·검증 → DB 트랜잭션(handler) → ack다. handler가 예외 없이 돌아온 뒤에만 offset을 commit하므로, 실패한 record는 완료로 건너뛰지 않는다.
 * 예외는 삼키지 않고 그대로 올려 error handler가 컨테이너를 멈추게 한다.
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class OrderConfirmedListener {

    public static final String LISTENER_ID = "order-activity-listener";

    private final OrderConfirmedParser parser;
    private final PurchaseActivityHandler handler;

    @KafkaListener(
        id = LISTENER_ID,
        topics = "${order-event.topic-name}",
        groupId = "${order-event.consumer-group}",
        containerFactory = OrderActivityKafkaConfig.FACTORY,
        autoStartup = "${order-event.listener-auto-startup:true}"
    )
    public void listen(ConsumerRecord<String, byte[]> record, Acknowledgment acknowledgment) {
        try {
            OrderConfirmation confirmation = parser.parseAndValidate(record.key(), record.value());
            PurchaseActivityRecorder.Result result = handler.consume(confirmation);
            acknowledgment.acknowledge();
            log.info("OrderConfirmed 반영 완료: result={}, eventId={}, orderId={}, partition={}, offset={}",
                result, confirmation.eventId(), confirmation.orderId(), record.partition(), record.offset());
        } catch (RuntimeException e) {
            // 실패한 record는 ack하지 않고 예외를 그대로 올린다. error handler가 컨테이너를 멈추므로, 어떤 record에서 멈췄는지 남긴다
            log.error("OrderConfirmed 처리 실패(ack 안 함, listener 중단): topic={}, partition={}, offset={}, key={}",
                record.topic(), record.partition(), record.offset(), record.key(), e);
            throw e;
        }
    }
}
