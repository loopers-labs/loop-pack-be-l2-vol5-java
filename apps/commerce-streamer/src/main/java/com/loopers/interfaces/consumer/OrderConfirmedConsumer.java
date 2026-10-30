package com.loopers.interfaces.consumer;

import com.loopers.application.activity.PurchaseActivityFacade;
import com.loopers.config.kafka.OrderActivityKafkaConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * order-events 의 OrderConfirmed → 상품 구매 활동. record 하나씩, 구매 활동 DB 트랜잭션이 commit 된 뒤에만 ack 한다 (at-least-once).
 * 예외는 삼키지 않는다: error handler 가 재시도하고, 소진·계약 위반이면 offset 을 넘기지 않고 컨테이너를 멈춘다 (OrderActivityKafkaConfig).
 * 새 group 은 로그 처음부터 읽는다 (auto.offset.reset=earliest): group 이 붙기 전에 발행된 확정도 빠뜨리지 않는다.
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class OrderConfirmedConsumer {
    public static final String LISTENER_ID = "purchase-activity-order-confirmed";

    private final OrderConfirmedMessageParser parser;
    private final PurchaseActivityFacade purchaseActivityFacade;

    @KafkaListener(
        id = LISTENER_ID,
        topics = "${kafka.topics.order-events.name}",
        groupId = "${kafka.consumers.purchase-activity.group-id}",
        containerFactory = OrderActivityKafkaConfig.ORDER_ACTIVITY_LISTENER,
        properties = "auto.offset.reset=earliest",
        autoStartup = "${kafka.consumers.purchase-activity.auto-startup:true}"
    )
    public void consume(ConsumerRecord<String, byte[]> record, Acknowledgment acknowledgment) {
        PurchaseActivityFacade.Result result = purchaseActivityFacade.consume(parser.parse(record.key(), record.value()));
        acknowledgment.acknowledge();
        log.info("OrderConfirmed 소비 [topic = {}, partition = {}, offset = {}, key = {}, result = {}]",
            record.topic(), record.partition(), record.offset(), record.key(), result);
    }
}
