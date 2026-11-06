package com.loopers.infrastructure.outbox;

import com.loopers.domain.outbox.OutboxSender;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.HashMap;

/** A separate raw-JSON producer; leaves the preserved W6 typed producer and streamer untouched. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KafkaProperties.class)
public class OutboxKafkaConfiguration {
    @Bean
    public DefaultKafkaProducerFactory<String, String> outboxProducerFactory(KafkaProperties properties) {
        var config = new HashMap<String, Object>(properties.buildProducerProperties());
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        return new DefaultKafkaProducerFactory<>(config, new StringSerializer(), new StringSerializer());
    }

    @Bean
    public KafkaTemplate<String, String> outboxKafkaTemplate(
            @Qualifier("outboxProducerFactory") DefaultKafkaProducerFactory<String, String> factory) {
        var template = new KafkaTemplate<>(factory);
        // The relay records safe identifiers/errors; disable the default full-record failure logger.
        template.setProducerListener(null);
        return template;
    }

    @Bean
    public OutboxSender outboxSender(@Qualifier("outboxKafkaTemplate") KafkaTemplate<String, String> template) {
        return new KafkaOutboxSender(template);
    }
}
