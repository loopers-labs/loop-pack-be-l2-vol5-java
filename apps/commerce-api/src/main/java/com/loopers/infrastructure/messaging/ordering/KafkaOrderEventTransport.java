package com.loopers.infrastructure.messaging.ordering;

import com.loopers.application.ordering.gateway.OrderEventTransport;
import java.util.concurrent.CompletableFuture;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class KafkaOrderEventTransport implements OrderEventTransport {
    private final KafkaTemplate<String, byte[]> template;
    private final String topic;

    public KafkaOrderEventTransport(@Qualifier("orderEventKafkaTemplate") KafkaTemplate<String, byte[]> template,
                                    @Value("${order-events.topic:order-events}") String topic) {
        this.template = template;
        this.topic = topic;
    }

    @Override
    public CompletableFuture<Published> send(String key, byte[] payload) {
        return template.send(topic, key, payload).thenApply(result -> {
            var metadata = result.getRecordMetadata();
            return new Published(metadata.topic(), metadata.partition(), metadata.offset());
        });
    }
}
