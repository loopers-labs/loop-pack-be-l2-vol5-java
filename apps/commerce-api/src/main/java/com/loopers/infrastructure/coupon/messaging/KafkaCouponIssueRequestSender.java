package com.loopers.infrastructure.coupon.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.coupon.gateway.CouponIssueRequestSender;
import com.loopers.domain.coupon.vo.IssueRequested;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 접수 전용 producer. 202 의 근거가 broker 확인이라 acks=all · 멱등을 명시한다 (8주차 D-132).
 * bean 으로 등록하면 Boot 의 기본 KafkaTemplate 이 빠지므로 안에서 만들고 직접 닫는다 (7주차 D-107 과 같음)
 */
@Slf4j
@Component
public class KafkaCouponIssueRequestSender implements CouponIssueRequestSender {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final DefaultKafkaProducerFactory<String, String> producerFactory;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final String topic;
    private final Duration sendTimeout;

    public KafkaCouponIssueRequestSender(
        KafkaProperties kafkaProperties,
        CouponIssueRequestProducerProperties properties,
        @Value("${coupon-issue.topic}") String topic
    ) {
        this.producerFactory = new DefaultKafkaProducerFactory<>(producerConfig(kafkaProperties, properties));
        this.kafkaTemplate = new KafkaTemplate<>(producerFactory);
        this.topic = topic;
        this.sendTimeout = properties.sendTimeout();
    }

    private static Map<String, Object> producerConfig(KafkaProperties kafkaProperties, CouponIssueRequestProducerProperties properties) {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaProperties.getBootstrapServers());
        config.put(ProducerConfig.CLIENT_ID_CONFIG, kafkaProperties.getClientId() + "-coupon-issue");
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, (int) properties.requestTimeout().toMillis());
        config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, (int) properties.deliveryTimeout().toMillis());
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, properties.maxBlock().toMillis());
        return config;
    }

    @Override
    public boolean send(IssueRequested request) {
        String payload = serialize(request);
        try {
            kafkaTemplate.send(topic, request.key(), payload).get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (TimeoutException e) {
            log.warn("접수 기록 전송 결과 모름(로컬 대기 {} 초과). key={}", sendTimeout, request.key());
            return false;
        } catch (ExecutionException e) {
            log.warn("접수 기록 전송 실패. key={} cause={}", request.key(), e.getCause().toString());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (RuntimeException e) {
            log.warn("접수 기록 전송 실패. key={} cause={}", request.key(), e.toString());
            return false;
        }
    }

    private static String serialize(IssueRequested request) {
        try {
            return OBJECT_MAPPER.writeValueAsString(CouponIssueRequestMessage.from(request));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("접수 기록 직렬화 실패. key=" + request.key(), e);
        }
    }

    @PreDestroy
    void close() {
        producerFactory.destroy();
    }
}
