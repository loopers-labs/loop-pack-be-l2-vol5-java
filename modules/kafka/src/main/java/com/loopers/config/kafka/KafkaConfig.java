package com.loopers.config.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.converter.BatchMessagingMessageConverter;
import org.springframework.kafka.support.converter.ByteArrayJsonMessageConverter;

import java.util.HashMap;
import java.util.Map;

@EnableKafka
@Configuration
@EnableConfigurationProperties({KafkaProperties.class, BatchListenerProperties.class})
public class KafkaConfig {
    public static final String BATCH_LISTENER = "BATCH_LISTENER_DEFAULT";

    @Bean
    public ByteArrayJsonMessageConverter jsonMessageConverter(ObjectMapper objectMapper) {
        return new ByteArrayJsonMessageConverter(objectMapper);
    }

    @Bean
    public KafkaTemplate<Object, Object> kafkaTemplate(ProducerFactory<Object, Object> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }

    @Bean(name = BATCH_LISTENER)
    public ConcurrentKafkaListenerContainerFactory<Object, Object> defaultBatchListenerContainerFactory(
            KafkaProperties kafkaProperties,
            BatchListenerProperties batchProperties,
            ByteArrayJsonMessageConverter converter
    ) {
        // batch 전용 poll·fetch·session 값은 이 factory에만 더한다. 공통 consumer 설정을 쓰는 다른 factory는 물려받지 않는다.
        Map<String, Object> consumerConfig = new HashMap<>(kafkaProperties.buildConsumerProperties());
        consumerConfig.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, batchProperties.maxPollRecords());
        consumerConfig.put(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, Math.toIntExact(batchProperties.fetchMinSize().toBytes()));
        consumerConfig.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, Math.toIntExact(batchProperties.fetchMaxWait().toMillis()));
        consumerConfig.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, Math.toIntExact(batchProperties.sessionTimeout().toMillis()));
        consumerConfig.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, Math.toIntExact(batchProperties.heartbeatInterval().toMillis()));
        consumerConfig.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, Math.toIntExact(batchProperties.maxPollInterval().toMillis()));

        ConcurrentKafkaListenerContainerFactory<Object, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(consumerConfig));
        factory.getContainerProperties().setAckMode(kafkaProperties.getListener().getAckMode());
        factory.setBatchMessageConverter(new BatchMessagingMessageConverter(converter));
        factory.setConcurrency(kafkaProperties.getListener().getConcurrency());
        factory.setBatchListener(true);
        return factory;
    }
}
