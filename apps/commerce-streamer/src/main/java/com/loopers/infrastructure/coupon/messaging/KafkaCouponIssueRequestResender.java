package com.loopers.infrastructure.coupon.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.coupon.gateway.CouponIssueRequestResender;
import com.loopers.domain.coupon.vo.PendingConfirm;
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
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 확인 대기 재전송 전용 producer. 형식은 commerce-api 의 접수 메시지(v1)와 같다 (8주차 D-134, D-136).
 * 접수 시각은 확인 대기에 남긴 시각을 쓴다. consumer 는 이 값을 쓰지 않는다
 */
@Slf4j
@Component
public class KafkaCouponIssueRequestResender implements CouponIssueRequestResender {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int SCHEMA_VERSION = 1;

    private final DefaultKafkaProducerFactory<String, String> producerFactory;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final String topic;
    private final Duration sendTimeout;

    public KafkaCouponIssueRequestResender(
        KafkaProperties kafkaProperties,
        CouponIssueResendProducerProperties properties,
        @Value("${coupon-issue.topic}") String topic
    ) {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaProperties.getBootstrapServers());
        config.put(ProducerConfig.CLIENT_ID_CONFIG, kafkaProperties.getClientId() + "-coupon-issue-resend");
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, (int) properties.requestTimeout().toMillis());
        config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, (int) properties.deliveryTimeout().toMillis());
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, properties.maxBlock().toMillis());
        this.producerFactory = new DefaultKafkaProducerFactory<>(config);
        this.kafkaTemplate = new KafkaTemplate<>(producerFactory);
        this.topic = topic;
        this.sendTimeout = properties.sendTimeout();
    }

    @Override
    public boolean resend(PendingConfirm pending) {
        String key = pending.campaignId() + ":" + pending.userId();
        try {
            kafkaTemplate.send(topic, key, payload(pending)).get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (TimeoutException | ExecutionException e) {
            log.warn("확인 대기 재전송 실패 또는 결과 모름. key={} cause={}", key, e.toString());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (RuntimeException e) {
            log.warn("확인 대기 재전송 실패. key={} cause={}", key, e.toString());
            return false;
        }
    }

    private static String payload(PendingConfirm pending) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("schemaVersion", SCHEMA_VERSION);
        message.put("campaignId", pending.campaignId());
        message.put("userId", pending.userId());
        message.put("sequence", pending.sequence());
        message.put("requestedAt", Instant.ofEpochMilli(pending.acceptedAtMillis()).atZone(ZoneId.systemDefault()).toOffsetDateTime().toString());
        try {
            return OBJECT_MAPPER.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("재전송 메시지 직렬화 실패. campaignId=" + pending.campaignId(), e);
        }
    }

    @PreDestroy
    void close() {
        producerFactory.destroy();
    }
}
