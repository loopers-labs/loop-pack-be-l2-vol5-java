package com.loopers.infrastructure.order.outbox;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
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

    // delivery.timeout.ms는 send()가 반환된 뒤 성공·실패를 알리는 상한이다.
    // Future 관찰 시간이 더 길어야 일반적인 실패는 Relay의 timeout이 아니라 producer의 실제 원인으로 기록된다.
    @DisplayName("기본 설정에서 producer 전달 timeout은 Relay의 Future 관찰 시간보다 짧다.")
    @Test
    void producerReportsDeliveryResultBeforeRelayStopsWaiting() throws IOException {
        PropertySource<?> defaults = new YamlPropertySourceLoader()
            .load("application", new ClassPathResource("application.yml")).getFirst();

        Duration requestTimeout = duration(defaults, "order.confirmed-event.relay.request-timeout");
        Duration deliveryTimeout = duration(defaults, "order.confirmed-event.relay.delivery-timeout");
        Duration futureTimeout = duration(defaults, "order.confirmed-event.relay.future-timeout");

        assertThat(requestTimeout).isLessThanOrEqualTo(deliveryTimeout);
        assertThat(deliveryTimeout).isLessThan(futureTimeout);
    }

    private Duration duration(PropertySource<?> source, String name) {
        Object value = source.getProperty(name);
        assertThat(value).as(name).isNotNull();
        return DurationStyle.detectAndParse(value.toString());
    }
}
