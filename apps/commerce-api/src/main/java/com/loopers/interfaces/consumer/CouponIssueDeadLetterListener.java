package com.loopers.interfaces.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.coupon.CouponIssueDeadLetters;
import com.loopers.application.coupon.CouponIssueMessage;
import com.loopers.config.CouponIssueListenerConfig;
import com.loopers.domain.coupon.CouponIssuePublisher.CouponIssue;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;

@Component
class CouponIssueDeadLetterListener {

    private final ObjectMapper objectMapper;
    private final CouponIssueDeadLetters deadLetters;

    CouponIssueDeadLetterListener(ObjectMapper objectMapper, CouponIssueDeadLetters deadLetters) {
        this.objectMapper = objectMapper;
        this.deadLetters = deadLetters;
    }

    @KafkaListener(
        id = CouponIssueListenerConfig.DEAD_LETTER_LISTENER_ID,
        topics = "${kafka.topics.coupon-issues-dlt}",
        groupId = CouponIssueListenerConfig.DEAD_LETTER_GROUP,
        containerFactory = CouponIssueListenerConfig.DEAD_LETTER_FACTORY,
        autoStartup = CouponIssueListenerConfig.AUTO_STARTUP
    )
    void onMessage(ConsumerRecord<String, byte[]> consumerRecord, Acknowledgment acknowledgment) {
        deadLetters.record(messageKey(consumerRecord), read(consumerRecord.value()),
            payload(consumerRecord.value()), error(consumerRecord),
            Instant.ofEpochMilli(consumerRecord.timestamp()));
        acknowledgment.acknowledge();
    }

    private Optional<CouponIssue> read(byte[] value) {
        try {
            return Optional.of(CouponIssueMessage.read(objectMapper, value));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static String payload(byte[] value) {
        return value == null ? "" : new String(value, StandardCharsets.UTF_8);
    }

    private static String messageKey(ConsumerRecord<String, byte[]> consumerRecord) {
        Header topic = consumerRecord.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_TOPIC);
        Header partition = consumerRecord.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_PARTITION);
        Header offset = consumerRecord.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_OFFSET);
        if (topic == null || partition == null || offset == null) {
            return consumerRecord.topic() + "-" + consumerRecord.partition() + "@" + consumerRecord.offset();
        }
        return new String(topic.value(), StandardCharsets.UTF_8) + "-"
            + ByteBuffer.wrap(partition.value()).getInt() + "@" + ByteBuffer.wrap(offset.value()).getLong();
    }

    private static String error(ConsumerRecord<String, byte[]> consumerRecord) {
        Header message = consumerRecord.headers().lastHeader(KafkaHeaders.DLT_EXCEPTION_MESSAGE);
        return message == null ? "원인 헤더 없음" : new String(message.value(), StandardCharsets.UTF_8);
    }
}
