package com.loopers.interfaces.consumer;

import com.loopers.application.purchaseactivity.PurchaseActivityHandler;
import com.loopers.application.purchaseactivity.PurchaseActivityResult;
import com.loopers.config.OrderActivityKafkaConfig;
import com.loopers.domain.purchaseactivity.OrderConfirmation;
import com.loopers.domain.purchaseactivity.PurchaseActivityException;
import com.loopers.interfaces.consumer.OrderActivityConsumeException.Stage;
import com.loopers.interfaces.consumer.order.OrderConfirmedParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * OrderConfirmed record 를 구매 활동으로 반영한다(소비 설계 5·6절). parser → handler(TX 종료 후 반환) → 같은 consumer 스레드에서 ack 순서다.
 * listener 자체에 TX·@Async 를 두지 않는다. 실패는 ack 없이 안전한 오류로 바꿔 던져 container 를 중단시킨다.
 * 로그에는 record 위치·검증된 사건 ID·단계·오류 코드·예외 타입만 남기고 payload·검증 전 key·원문 오류 메시지는 남기지 않는다.
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class OrderActivityKafkaConsumer {

    private final OrderConfirmedParser parser;
    private final PurchaseActivityHandler handler;

    @KafkaListener(
        id = OrderActivityKafkaConfig.LISTENER_ID,
        topics = "${commerce.order-activity.topic}",
        groupId = "${commerce.order-activity.group-id}",
        autoStartup = "${commerce.order-activity.auto-startup}",
        containerFactory = OrderActivityKafkaConfig.LISTENER_CONTAINER_FACTORY
    )
    public void listen(ConsumerRecord<String, byte[]> record, Acknowledgment ack) {
        Stage stage = Stage.PARSE;
        try {
            OrderConfirmation event = parser.parseAndValidate(record.key(), record.value());
            stage = Stage.HANDLE;
            PurchaseActivityResult result = handler.consume(event);
            stage = Stage.ACK;
            ack.acknowledge();
            // 업무 TX 완료와 ACK 호출의 정상 반환이다. broker 의 group offset commit 완료를 확인한 로그가 아니다.
            log.info("ORDER_ACTIVITY_HANDLED_AND_ACK_RETURNED topic={} partition={} offset={} eventId={} result={}",
                record.topic(), record.partition(), record.offset(), event.eventId(), result);
        } catch (RuntimeException failure) {
            String code = failure instanceof PurchaseActivityException known ? known.getCode().name() : "NONE";
            log.warn("ORDER_ACTIVITY_FAILURE topic={} partition={} offset={} stage={} code={} exceptionType={}",
                record.topic(), record.partition(), record.offset(), stage, code, failure.getClass().getName());
            throw new OrderActivityConsumeException(stage, code, failure.getClass().getName(), record.topic(),
                record.partition(), record.offset());
        }
    }
}
