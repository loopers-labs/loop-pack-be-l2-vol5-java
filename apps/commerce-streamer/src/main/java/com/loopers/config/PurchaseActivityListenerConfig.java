package com.loopers.config;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.RetryListener;
import org.springframework.util.backoff.ExponentialBackOff;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Configuration
public class PurchaseActivityListenerConfig {

    public static final String FACTORY = "purchaseActivityListenerContainerFactory";
    public static final String LISTENER_ID = "purchase-activity";

    private static final int CONCURRENCY = 3;
    private static final long FIRST_BACKOFF_MILLIS = 1_000;
    private static final double BACKOFF_MULTIPLIER = 2.0;
    private static final long MAX_BACKOFF_MILLIS = 30_000;

    @Bean(name = FACTORY)
    public ConcurrentKafkaListenerContainerFactory<String, byte[]> purchaseActivityListenerContainerFactory(
        KafkaProperties kafkaProperties, MeterRegistry meterRegistry
    ) {
        Map<String, Object> consumer = new HashMap<>(kafkaProperties.buildConsumerProperties());
        consumer.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumer.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false);
        consumer.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

        ConcurrentKafkaListenerContainerFactory<String, byte[]> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(consumer));
        factory.setConcurrency(CONCURRENCY);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.BATCH);
        factory.getContainerProperties().setObservationEnabled(true);
        factory.setCommonErrorHandler(retryWithoutSkipping(meterRegistry));
        return factory;
    }

    private static DefaultErrorHandler retryWithoutSkipping(MeterRegistry meterRegistry) {
        ExponentialBackOff backOff = new ExponentialBackOff(FIRST_BACKOFF_MILLIS, BACKOFF_MULTIPLIER);
        backOff.setMaxInterval(MAX_BACKOFF_MILLIS);
        DefaultErrorHandler handler = new DefaultErrorHandler((failed, exception) -> {
            throw new IllegalStateException("구매 활동 record 는 건너뛰지 않는다: " + failed.topic() + "-"
                + failed.partition() + "@" + failed.offset(), exception);
        }, backOff);
        handler.setClassifications(Map.of(), true);
        handler.setRetryListeners(new RetryListener() {
            @Override
            public void failedDelivery(ConsumerRecord<?, ?> failed, Exception exception, int deliveryAttempt) {
                String cause = NestedExceptionUtils.getMostSpecificCause(exception).getClass().getSimpleName();
                meterRegistry.counter("purchase.activity.retry", "cause", cause).increment();
                log.warn("주문 이벤트 처리가 실패해 같은 record 를 다시 시도한다: position={}-{}@{}, attempt={}, cause={}",
                    failed.topic(), failed.partition(), failed.offset(), deliveryAttempt, cause, exception);
            }
        });
        return handler;
    }
}
