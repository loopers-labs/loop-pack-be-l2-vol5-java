package com.loopers.infrastructure.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.loopers.domain.order.OrderConfirmedEvent;
import com.loopers.domain.event.DomainEvent;
import com.loopers.domain.event.DomainEventPublisher;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.HashMap;
import java.util.List;

/** API producer only. Does not change the streamer's existing consumer factory or the DB transaction manager. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KafkaProperties.class)
public class DomainEventKafkaConfiguration {
    @Bean
    public DefaultKafkaProducerFactory<String, DomainEvent> domainEventProducerFactory(
            KafkaProperties properties, ObjectMapper mapper) {
        var config = new HashMap<String, Object>(properties.buildProducerProperties());
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        config.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, false);
        var serializer = new JsonSerializer<DomainEvent>(mapper.copy()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS));
        return new DefaultKafkaProducerFactory<>(config, new StringSerializer(), serializer);
    }

    @Bean
    public KafkaTemplate<String, DomainEvent> domainEventKafkaTemplate(
            DefaultKafkaProducerFactory<String, DomainEvent> domainEventProducerFactory) {
        var template = new KafkaTemplate<>(domainEventProducerFactory);
        // The adapter records both outcomes with whitelisted fields, without the default full-record error log.
        template.setProducerListener(null);
        return template;
    }

    @Bean
    public KafkaEventRoute orderConfirmedEventRoute(
            @Value("${commerce.order-events.enabled:true}") boolean enabled,
            @Value("${commerce.order-events.topic:order-events}") String topic) {
        return new KafkaEventRoute(OrderConfirmedEvent.class, topic, enabled);
    }

    @Bean
    public DomainEventPublisher domainEventPublisher(
            KafkaTemplate<String, DomainEvent> domainEventKafkaTemplate, List<KafkaEventRoute> routes) {
        return new KafkaDomainEventPublisher(domainEventKafkaTemplate, routes);
    }
}
