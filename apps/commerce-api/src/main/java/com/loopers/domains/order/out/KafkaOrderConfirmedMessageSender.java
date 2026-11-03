package com.loopers.domains.order.out;

import com.loopers.domains.order.app.port.OrderConfirmedMessageSender;
import com.loopers.domains.order.app.port.SendOutcome;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class KafkaOrderConfirmedMessageSender implements OrderConfirmedMessageSender {
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final String topic;
    private final Duration sendTimeout;

    public KafkaOrderConfirmedMessageSender(KafkaTemplate<String, String> kafkaTemplate,
        @Value("${commerce.kafka.topics.order-events}") String topic,
        @Value("${commerce.outbox.relay.send-timeout}") Duration sendTimeout) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
        this.sendTimeout = sendTimeout;
    }

    @Override
    public SendOutcome send(String key, String payload) {
        return new SendOutcome.RowFailure("구현 전");
    }

    static SendOutcome classify(Throwable failure) {
        return new SendOutcome.RowFailure(String.valueOf(failure));
    }
}
