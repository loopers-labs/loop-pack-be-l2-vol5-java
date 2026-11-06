package com.loopers.infrastructure.order.outbox;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Duration;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

class OrderConfirmedOutboxKafkaConfigTest {

    @Test
    void templateDestroysItsDedicatedFactoryAndKeepsSharedFactoryAlive() {
        Map<String, Object> configuration = Map.of(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "unused:9092",
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class
        );
        DefaultKafkaProducerFactory<Object, Object> shared = spy(new DefaultKafkaProducerFactory<>(configuration));
        DefaultKafkaProducerFactory<Object, Object> dedicated = spy(new DefaultKafkaProducerFactory<>(configuration));
        doReturn(dedicated).when(shared).copyWithConfigurationOverride(anyMap());

        KafkaTemplate<Object, Object> template = new OrderConfirmedOutboxKafkaConfig()
            .orderConfirmedOutboxKafkaTemplate(shared, Duration.ofSeconds(2), Duration.ofSeconds(5),
                Duration.ofSeconds(1));
        template.destroy();

        verify(dedicated).destroy();
        verify(shared, never()).destroy();
    }
}
