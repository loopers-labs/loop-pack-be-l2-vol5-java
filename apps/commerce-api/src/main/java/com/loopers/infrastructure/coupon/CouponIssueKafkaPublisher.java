package com.loopers.infrastructure.coupon;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.coupon.CouponIssuePublisher;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Component
class CouponIssueKafkaPublisher implements CouponIssuePublisher {

    private static final String METRIC = "coupon.issue.publish";

    private final KafkaTemplate<String, byte[]> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final String topic;
    private final Duration ackTimeout;

    CouponIssueKafkaPublisher(KafkaTemplate<String, byte[]> kafkaTemplate, ObjectMapper objectMapper,
                              MeterRegistry meterRegistry, @Value("${kafka.topics.coupon-issues}") String topic,
                              CouponIssuePublishProperties properties) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.topic = topic;
        this.ackTimeout = properties.ackTimeout();
    }

    @Override
    public Outcome publish(CouponIssue issue) {
        try {
            byte[] payload = CouponIssueMessage.from(issue).toBytes(objectMapper);
            RecordMetadata metadata = kafkaTemplate.send(topic, String.valueOf(issue.campaignId()), payload)
                .get(ackTimeout.toMillis(), TimeUnit.MILLISECONDS)
                .getRecordMetadata();
            log.debug("쿠폰 접수를 보냈다: campaignId={}, userId={}, issueNumber={}, partition={}, offset={}",
                issue.campaignId(), issue.userId(), issue.issueNumber(), metadata.partition(), metadata.offset());
            count("success", "none");
            return new Outcome.Published();
        } catch (ExecutionException e) {
            return unconfirmed(issue, e.getCause());
        } catch (TimeoutException | JsonProcessingException | RuntimeException e) {
            return unconfirmed(issue, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return unconfirmed(issue, e);
        }
    }

    private Outcome unconfirmed(CouponIssue issue, Throwable ex) {
        Throwable cause = NestedExceptionUtils.getMostSpecificCause(ex);
        String error = cause.getClass().getSimpleName() + ": " + cause.getMessage();
        log.warn("쿠폰 접수를 보냈는지 확인하지 못했다: campaignId={}, userId={}, issueNumber={}, error={}",
            issue.campaignId(), issue.userId(), issue.issueNumber(), error);
        count("unconfirmed", cause.getClass().getSimpleName());
        return new Outcome.Unconfirmed(error);
    }

    private void count(String result, String cause) {
        meterRegistry.counter(METRIC, "result", result, "cause", cause).increment();
    }
}
