package com.loopers.interfaces.consumer;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerPausingBackOffHandler;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.ListenerContainerPauseService;
import org.springframework.kafka.listener.ListenerContainerRegistry;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.util.backoff.ExponentialBackOff;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * 구매 활동 전용 record listener. 기존 batch factory(demo)는 바꾸지 않음 (6주차 D-88).
 * record 마다 DB commit 뒤 consumer 스레드에서 ack 해 그 즉시 offset 을 commit 함 (설계 5.1)
 */
@Configuration
public class OrderConfirmedConsumerConfig {

    public static final String CONTAINER_FACTORY = "orderConfirmedListenerContainerFactory";

    /** error handler 를 bean 으로 두면 Boot 의 기본 listener factory 에도 붙으므로 여기서만 만듦 */
    @Bean(name = CONTAINER_FACTORY)
    public ConcurrentKafkaListenerContainerFactory<String, byte[]> orderConfirmedListenerContainerFactory(
        KafkaProperties kafkaProperties,
        KafkaTemplate<String, byte[]> orderConfirmedDltTemplate,
        ListenerContainerRegistry registry,
        ThreadPoolTaskScheduler orderConfirmedPauseScheduler,
        @Value("${order-event.retry.initial-interval}") Duration initialInterval,
        @Value("${order-event.retry.multiplier}") double multiplier,
        @Value("${order-event.retry.max-interval}") Duration maxInterval,
        @Value("${order-event.retry.max-elapsed}") Duration maxElapsed
    ) {
        Map<String, Object> config = new HashMap<>(kafkaProperties.buildConsumerProperties());
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        // committed offset 이 없을 때(첫 배포 · 만료)만 쓰임. latest 면 그 사이 구매 사실이 조용히 빠짐 (D-92)
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        ExponentialBackOff backOff = new ExponentialBackOff(initialInterval.toMillis(), multiplier);
        backOff.setMaxInterval(maxInterval.toMillis());
        backOff.setMaxElapsedTime(maxElapsed.toMillis());
        // sleep 대신 pause 로 기다려 poll 을 계속함. group 에 남아 rebalance 없이 긴 대기를 견딤 (D-87)
        ContainerPausingBackOffHandler backOffHandler = new ContainerPausingBackOffHandler(
            new ListenerContainerPauseService(registry, orderConfirmedPauseScheduler));
        // 라이브러리 기본 이름(3.3 은 "-dlt")에 기대지 않고 인프라가 만든 topic + ".DLT" 의 같은 partition 으로 보냄 (D-89, D-90)
        DeadLetterPublishingRecoverer dlt = new DeadLetterPublishingRecoverer(orderConfirmedDltTemplate,
            (record, exception) -> new TopicPartition(record.topic() + ".DLT", record.partition()));
        StopAfterRetriesErrorHandler errorHandler = new StopAfterRetriesErrorHandler(dlt, backOff, backOffHandler);

        ConcurrentKafkaListenerContainerFactory<String, byte[]> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(config));
        factory.setBatchListener(false);
        factory.setConcurrency(1);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }

    @Bean
    public ThreadPoolTaskScheduler orderConfirmedPauseScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setThreadNamePrefix("order-confirmed-resume-");
        return scheduler;
    }

    /** 공용 producer 는 value 를 JSON 으로 감싸므로, 받은 바이트를 그대로 옮기도록 따로 둠 */
    @Bean
    public ProducerFactory<String, byte[]> orderConfirmedDltProducerFactory(KafkaProperties kafkaProperties) {
        Map<String, Object> config = new HashMap<>(kafkaProperties.buildProducerProperties());
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        return new DefaultKafkaProducerFactory<>(config);
    }

    @Bean
    public KafkaTemplate<String, byte[]> orderConfirmedDltTemplate(ProducerFactory<String, byte[]> orderConfirmedDltProducerFactory) {
        return new KafkaTemplate<>(orderConfirmedDltProducerFactory);
    }
}
