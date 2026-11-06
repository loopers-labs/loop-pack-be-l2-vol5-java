package com.loopers.application.order.fixture;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.loopers.kafka.event.OrderConfirmed;
import java.time.Duration;
import java.util.HashMap;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@TestConfiguration(proxyBeanMethods = false)
public class LegacyWeek6Publication {
    @Bean
    ProducerFactory<String, OrderConfirmed> orderConfirmedProducerFactory(
            KafkaProperties properties, ObjectMapper objectMapper,
            @Value("${order-events.publish-timeout:5s}") Duration publishTimeout) {
        var config = new HashMap<>(properties.buildProducerProperties());
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, publishTimeout.toMillis());
        config.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, false);
        var serializer = new JsonSerializer<OrderConfirmed>(
                objectMapper.copy().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS));
        return new DefaultKafkaProducerFactory<>(config, new StringSerializer(), serializer);
    }

    @Bean
    KafkaTemplate<String, OrderConfirmed> orderConfirmedKafkaTemplate(
            @Qualifier("orderConfirmedProducerFactory") ProducerFactory<String, OrderConfirmed> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }

    @Bean
    OrderConfirmedPublisher orderConfirmedPublisher(
            @Qualifier("orderConfirmedKafkaTemplate") KafkaTemplate<String, OrderConfirmed> kafka,
            @Value("${order-events.topic:order-events}") String topic,
            @Value("${order-events.publish-timeout:5s}") Duration publishTimeout) {
        return new KafkaOrderConfirmedPublisher(kafka, topic, publishTimeout);
    }

    @Bean
    LegacyPublisher legacyWeek6Publisher(OrderConfirmedPublisher publisher) {
        return new LegacyPublisher(publisher);
    }

    static class LegacyPublisher {
        private static final Logger log = LoggerFactory.getLogger(LegacyPublisher.class);
        private final OrderConfirmedPublisher publisher;

        LegacyPublisher(OrderConfirmedPublisher publisher) {
            this.publisher = publisher;
        }

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        public void publish(OrderConfirmed event) {
            try {
                var sent = publisher.publish(event);
                log.info("order_confirmation_published eventId={} orderId={} topic={} partition={} offset={}",
                        event.eventId(), event.orderId(), sent.topic(), sent.partition(), sent.offset());
            } catch (RuntimeException exception) {
                log.error("order_confirmation_publication_unconfirmed eventId={} orderId={} confirmedAt={}",
                        event.eventId(), event.orderId(), event.confirmedAt(), exception);
            }
        }
    }
}
