package com.loopers.infrastructure.order;

import com.loopers.domain.order.OrderConfirmedEvent;
import com.loopers.domain.order.OrderEventPublication;
import com.loopers.domain.order.OrderEventPublisher;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * 확정 주문 이벤트를 {@code order-events} topic에 보낸다. key는 주문 id의 문자열이다.
 *
 * <p>send 결과를 기다려 broker가 알려 준 topic·partition·offset을 돌려주고, 실패·timeout은 예외가 아니라 {@link OrderEventPublication.Failed}로 돌려준다.
 * 호출자는 이미 commit된 주문 응답을 만드는 중이라, 여기서 예외가 올라가면 안 된다.
 */
@Component
public class KafkaOrderEventPublisher implements OrderEventPublisher {

    private final KafkaTemplate<Object, Object> kafkaTemplate;
    private final String topic;
    private final Duration sendTimeout;

    public KafkaOrderEventPublisher(
        KafkaTemplate<Object, Object> kafkaTemplate,
        @Value("${order-event.topic-name}") String topic,
        @Value("${order-event.send-timeout}") Duration sendTimeout
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
        this.sendTimeout = sendTimeout;
    }

    @Override
    public OrderEventPublication publish(OrderConfirmedEvent event) {
        try {
            SendResult<Object, Object> result = kafkaTemplate.send(topic, event.key(), event)
                .get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
            RecordMetadata metadata = result.getRecordMetadata();
            return new OrderEventPublication.Sent(metadata.topic(), metadata.partition(), metadata.offset());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new OrderEventPublication.Failed("send 결과를 기다리다 중단됐습니다");
        } catch (Exception e) {
            return new OrderEventPublication.Failed(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
