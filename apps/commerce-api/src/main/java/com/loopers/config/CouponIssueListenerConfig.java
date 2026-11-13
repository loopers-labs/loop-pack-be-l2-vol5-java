package com.loopers.config;

import com.loopers.support.error.DomainException;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
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
    public static final String DEAD_LETTER_FACTORY = "couponIssueDeadLetterListenerContainerFactory";
    public static final String DEAD_LETTER_LISTENER_ID = "coupon-issue-dlt";
    public static final String DEAD_LETTER_GROUP = "coupon-issue-dlt";

    private static final long FIRST_BACKOFF_MILLIS = 1_000;
    private static final double BACKOFF_MULTIPLIER = 2.0;
    private static final long MAX_BACKOFF_MILLIS = 30_000;
    private static final int ANY_PARTITION = -1;
    private static final int MAX_POLL_RECORDS = 50;

    @Bean(name = FACTORY)
    public ConcurrentKafkaListenerContainerFactory<String, byte[]> couponIssueListenerContainerFactory(
        KafkaProperties kafkaProperties, CouponIssueWorkerProperties properties, MeterRegistry meterRegistry,
        KafkaTemplate<String, byte[]> kafkaTemplate, @Value("${kafka.topics.coupon-issues-dlt}") String deadLetterTopic
    ) {
        Map<String, Object> consumer = new HashMap<>(kafkaProperties.buildConsumerProperties());
        consumer.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumer.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false);
        consumer.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        consumer.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, MAX_POLL_RECORDS);

        ConcurrentKafkaListenerContainerFactory<String, byte[]> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(consumer));
        factory.setConcurrency(properties.concurrency());
        factory.setBatchListener(true);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        factory.getContainerProperties().setObservationEnabled(true);
        factory.setCommonErrorHandler(retryInfraFailuresAndDeadLetterBugs(kafkaTemplate, deadLetterTopic, meterRegistry));
        return factory;
    }

    @Bean(name = DEAD_LETTER_FACTORY)
    public ConcurrentKafkaListenerContainerFactory<String, byte[]> couponIssueDeadLetterListenerContainerFactory(
        KafkaProperties kafkaProperties, MeterRegistry meterRegistry
    ) {
        Map<String, Object> consumer = new HashMap<>(kafkaProperties.buildConsumerProperties());
        consumer.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumer.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false);
        consumer.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

        ConcurrentKafkaListenerContainerFactory<String, byte[]> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(consumer));
        factory.setConcurrency(1);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        factory.getContainerProperties().setObservationEnabled(true);
        factory.setCommonErrorHandler(retryWithoutSkipping(meterRegistry));
        return factory;
    }

    private static DefaultErrorHandler retryWithoutSkipping(MeterRegistry meterRegistry) {
        ExponentialBackOff backOff = new ExponentialBackOff(FIRST_BACKOFF_MILLIS, BACKOFF_MULTIPLIER);
        backOff.setMaxInterval(MAX_BACKOFF_MILLIS);
        DefaultErrorHandler handler = new DefaultErrorHandler((failed, exception) -> {
            throw new IllegalStateException("DLT record 는 건너뛰지 않는다: " + failed.topic() + "-"
                + failed.partition() + "@" + failed.offset(), exception);
        }, backOff);
        handler.setClassifications(Map.of(), true);
        handler.setRetryListeners((failed, exception, deliveryAttempt) -> {
            String cause = NestedExceptionUtils.getMostSpecificCause(exception).getClass().getSimpleName();
            meterRegistry.counter("coupon.issue.dead_letter.retry", "cause", cause).increment();
            log.warn("DLT 로그 저장이 실패해 같은 record 를 다시 시도한다: position={}-{}@{}, attempt={}, cause={}",
                failed.topic(), failed.partition(), failed.offset(), deliveryAttempt, cause, exception);
        });
        return handler;
    }

    @SuppressWarnings("unchecked")
    private static DefaultErrorHandler retryInfraFailuresAndDeadLetterBugs(KafkaTemplate<String, byte[]> kafkaTemplate,
                                                                            String deadLetterTopic,
                                                                            MeterRegistry meterRegistry) {
        KafkaOperations<Object, Object> template = (KafkaOperations<Object, Object>) (KafkaOperations<?, ?>) kafkaTemplate;
        DeadLetterPublishingRecoverer toDeadLetterTopic = new DeadLetterPublishingRecoverer(template,
            (failed, exception) -> new TopicPartition(deadLetterTopic, ANY_PARTITION));
        ExponentialBackOff backOff = new ExponentialBackOff(FIRST_BACKOFF_MILLIS, BACKOFF_MULTIPLIER);
        backOff.setMaxInterval(MAX_BACKOFF_MILLIS);
        DefaultErrorHandler handler = new DefaultErrorHandler(toDeadLetterTopic, backOff);
        handler.setClassifications(Map.of(), true);
        handler.addNotRetryableExceptions(DomainException.class, IllegalStateException.class);
        handler.setRetryListeners(new RetryListener() {
            @Override
            public void failedDelivery(ConsumerRecord<?, ?> failed, Exception exception, int deliveryAttempt) {
                String cause = NestedExceptionUtils.getMostSpecificCause(exception).getClass().getSimpleName();
                if (deliveryAttempt > 1) {
                    meterRegistry.counter("coupon.issue.worker.retry", "cause", cause).increment();
                }
                log.warn("쿠폰 기록이 실패했다: position={}-{}@{}, attempt={}, cause={}",
                    failed.topic(), failed.partition(), failed.offset(), deliveryAttempt, cause, exception);
            }

            @Override
            public void recovered(ConsumerRecord<?, ?> failed, Exception exception) {
                String cause = NestedExceptionUtils.getMostSpecificCause(exception).getClass().getSimpleName();
                meterRegistry.counter("coupon.issue.worker.dead_letter", "cause", cause).increment();
                log.error("쿠폰 기록을 다시 해도 같을 실패라 DLT 로 보냈다: position={}-{}@{}, cause={}",
                    failed.topic(), failed.partition(), failed.offset(), cause, exception);
            }
        });
        return handler;
    }
}
