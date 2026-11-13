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
public class CouponIssueListenerConfig {

    public static final String FACTORY = "couponIssueListenerContainerFactory";
    public static final String LISTENER_ID = "coupon-issue";
    public static final String GROUP = "coupon-issue";
    public static final String AUTO_STARTUP = "${coupon.issue.worker.auto-startup}";

    private static final long FIRST_BACKOFF_MILLIS = 1_000;
    private static final double BACKOFF_MULTIPLIER = 2.0;
    private static final long MAX_BACKOFF_MILLIS = 30_000;

    @Bean(name = FACTORY)
    public ConcurrentKafkaListenerContainerFactory<String, byte[]> couponIssueListenerContainerFactory(
        KafkaProperties kafkaProperties, CouponIssueWorkerProperties properties, MeterRegistry meterRegistry
    ) {
        Map<String, Object> consumer = new HashMap<>(kafkaProperties.buildConsumerProperties());
        consumer.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumer.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false);
        consumer.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

        ConcurrentKafkaListenerContainerFactory<String, byte[]> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(consumer));
        factory.setConcurrency(properties.concurrency());
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        factory.getContainerProperties().setObservationEnabled(true);
        factory.setCommonErrorHandler(retryWithoutSkipping(meterRegistry));
        return factory;
    }

    private static DefaultErrorHandler retryWithoutSkipping(MeterRegistry meterRegistry) {
        ExponentialBackOff backOff = new ExponentialBackOff(FIRST_BACKOFF_MILLIS, BACKOFF_MULTIPLIER);
        backOff.setMaxInterval(MAX_BACKOFF_MILLIS);
        DefaultErrorHandler handler = new DefaultErrorHandler((failed, exception) -> {
            throw new IllegalStateException("쿠폰 접수 record 는 건너뛰지 않는다: " + failed.topic() + "-"
                + failed.partition() + "@" + failed.offset(), exception);
        }, backOff);
        handler.setClassifications(Map.of(), true);
        handler.setRetryListeners(new RetryListener() {
            @Override
            public void failedDelivery(ConsumerRecord<?, ?> failed, Exception exception, int deliveryAttempt) {
                String cause = NestedExceptionUtils.getMostSpecificCause(exception).getClass().getSimpleName();
                meterRegistry.counter("coupon.issue.worker.retry", "cause", cause).increment();
                log.warn("쿠폰 기록이 실패해 같은 record 를 다시 시도한다: position={}-{}@{}, attempt={}, cause={}",
                    failed.topic(), failed.partition(), failed.offset(), deliveryAttempt, cause, exception);
            }
        });
        return handler;
    }
}
