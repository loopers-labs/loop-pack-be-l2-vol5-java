package com.loopers.purchase.adapter.in.kafka;

import com.loopers.config.PurchaseActivityKafkaConfig;
import com.loopers.purchase.application.port.in.PurchaseActivityUseCase;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * order-events → 상품 구매 활동 (W6 D-7). record 하나 = DB 트랜잭션 하나 = ack 하나.
 * handler가 정상 반환(저장 또는 no-op)했을 때만 ack한다. 예외면 ack하지 않고 error handler가 재시도·DLT를 정한다 (ADR-W6-06).
 */
@RequiredArgsConstructor
@Component
public class OrderConfirmedListener {

    private final OrderConfirmedMessageParser parser;
    private final PurchaseActivityUseCase purchaseActivityUseCase;

    @KafkaListener(
        id = PurchaseActivityKafkaConfig.LISTENER_ID,
        topics = "${kafka.topics.order-events}",
        groupId = "${kafka.groups.purchase-activity}",
        containerFactory = PurchaseActivityKafkaConfig.RECORD_LISTENER
    )
    public void on(ConsumerRecord<String, String> record, Acknowledgment ack) {
        purchaseActivityUseCase.consume(parser.parse(record), record.value());
        ack.acknowledge();
    }
}
