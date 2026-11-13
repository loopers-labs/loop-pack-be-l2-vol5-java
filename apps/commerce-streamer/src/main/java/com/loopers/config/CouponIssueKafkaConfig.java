package com.loopers.config;

import com.loopers.confg.kafka.KafkaConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.CooperativeStickyAssignor;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 쿠폰 발급 consumer 설정 (D-6·D-8). 구매 활동 consumer(W6)와 같은 방식이다: record 하나 = TX 하나 = ack 하나, 재시도 3회 뒤 DLT.
 * <p>
 * 동시 처리 수(CONCURRENCY)는 격벽이다 (D-6) — 쿠폰 발급이 DB 커넥션을 이 수 이상 쥐지 않는다. 값은 실측(필수 실험 ⑧)으로 다시 정한다.
 */
@Configuration
public class CouponIssueKafkaConfig {

    public static final String RECORD_LISTENER = "COUPON_ISSUE_RECORD_LISTENER";
    public static final String LISTENER_ID = "coupon-issue";
    public static final String DLT_SUFFIX = ".DLT";
    public static final int CONCURRENCY = 2;
    public static final int MAX_RETRIES = 3;

    @Bean(name = RECORD_LISTENER)
    public ConcurrentKafkaListenerContainerFactory<String, String> couponIssueListenerFactory(
        KafkaProperties kafkaProperties,
        @Qualifier(KafkaConfig.STRING_TEMPLATE) KafkaTemplate<String, String> stringKafkaTemplate
    ) {
        Map<String, Object> consumerConfig = new HashMap<>(kafkaProperties.buildConsumerProperties());
        consumerConfig.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerConfig.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerConfig.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        consumerConfig.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerConfig.put(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG, List.of(CooperativeStickyAssignor.class.getName()));

        ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(consumerConfig));
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.setConcurrency(CONCURRENCY);
        factory.setCommonErrorHandler(errorHandler(stringKafkaTemplate));
        return factory;
    }

    private static DefaultErrorHandler errorHandler(KafkaTemplate<String, String> template) {
        // W6과 같은 이유로 DLT 이름(.DLT)과 partition(-1, key로 고름)을 직접 정한다.
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template,
            (record, exception) -> new TopicPartition(record.topic() + DLT_SUFFIX, -1));
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(MAX_RETRIES);
        backOff.setInitialInterval(1_000L);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(4_000L);
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.setCommitRecovered(true);
        return handler;
    }

    @Bean
    NewTopic couponIssueDeadLetterTopic(@Value("${kafka.topics.coupon-issue}") String topic) {
        return TopicBuilder.name(topic + DLT_SUFFIX)
            .partitions(3)
            .replicas(3)
            .config("min.insync.replicas", "2")
            .build();
    }

    @Bean
    Clock couponIssueClock() {
        return Clock.systemDefaultZone();
    }
}
