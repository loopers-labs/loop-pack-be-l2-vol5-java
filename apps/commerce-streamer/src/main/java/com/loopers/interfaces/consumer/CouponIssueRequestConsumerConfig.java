package com.loopers.interfaces.consumer;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerPausingBackOffHandler;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ListenerContainerPauseService;
import org.springframework.kafka.listener.ListenerContainerRegistry;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.util.backoff.ExponentialBackOff;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * 접수 기록 적재 전용 batch listener. 묶음마다 DB commit 뒤 ack 한다 (8주차 D-132).
 * 일시 오류는 상한 없이 pause 한 채 재시도한다. 적재를 멈추면 접수 약속이 DB 에 남지 않는다 (D-126 과 같은 기준)
 */
@Configuration
public class CouponIssueRequestConsumerConfig {

    public static final String CONTAINER_FACTORY = "couponIssueRequestListenerContainerFactory";

    @Bean(name = CONTAINER_FACTORY)
    public ConcurrentKafkaListenerContainerFactory<String, byte[]> couponIssueRequestListenerContainerFactory(
        KafkaProperties kafkaProperties,
        ListenerContainerRegistry registry,
        ThreadPoolTaskScheduler couponIssueRequestPauseScheduler,
        @Value("${coupon-issue.max-poll-records}") int maxPollRecords,
        @Value("${coupon-issue.retry.initial-interval}") Duration initialInterval,
        @Value("${coupon-issue.retry.multiplier}") double multiplier,
        @Value("${coupon-issue.retry.max-interval}") Duration maxInterval
    ) {
        Map<String, Object> config = new HashMap<>(kafkaProperties.buildConsumerProperties());
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        // committed offset 이 없을 때만 쓰임. latest 면 그 사이 접수 약속이 조용히 빠짐
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, maxPollRecords);

        ExponentialBackOff backOff = new ExponentialBackOff(initialInterval.toMillis(), multiplier);
        backOff.setMaxInterval(maxInterval.toMillis());
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(null, backOff, new ContainerPausingBackOffHandler(
            new ListenerContainerPauseService(registry, couponIssueRequestPauseScheduler)));

        ConcurrentKafkaListenerContainerFactory<String, byte[]> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(config));
        factory.setBatchListener(true);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }

    @Bean
    public ThreadPoolTaskScheduler couponIssueRequestPauseScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setThreadNamePrefix("coupon-issue-request-resume-");
        return scheduler;
    }
}
