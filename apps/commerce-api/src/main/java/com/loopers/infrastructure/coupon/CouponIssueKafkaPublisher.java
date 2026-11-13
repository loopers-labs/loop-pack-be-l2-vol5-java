package com.loopers.infrastructure.coupon;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.coupon.CouponIssueMessage;
import com.loopers.domain.coupon.CouponIssuePublisher;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
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
    private final Timer ackLatency;

    CouponIssueKafkaPublisher(KafkaTemplate<String, byte[]> kafkaTemplate, ObjectMapper objectMapper,
                              MeterRegistry meterRegistry, @Value("${kafka.topics.coupon-issues}") String topic,
                              CouponIssuePublishProperties properties) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.topic = topic;
        this.ackTimeout = properties.ackTimeout();
        this.ackLatency = Timer.builder(METRIC + ".ack")
            .publishPercentiles(0.5, 0.99)
            .register(meterRegistry);
    }

    @Override
    public Outcome publish(CouponIssue issue) {
        try {
            byte[] payload = CouponIssueMessage.from(issue).toBytes(objectMapper);
            long started = System.nanoTime();
            RecordMetadata metadata = kafkaTemplate.send(topic, String.valueOf(issue.userId()), payload)
                .get(ackTimeout.toMillis(), TimeUnit.MILLISECONDS)
                .getRecordMetadata();
            ackLatency.record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
            log.debug("쿠폰 접수를 보냈다: campaignId={}, userId={}, issueNumber={}, partition={}, offset={}",
                issue.campaignId(), issue.userId(), issue.issueNumber(), metadata.partition(), metadata.offset());
            count("success", "none");
            return new Outcome.Published();
        } catch (TimeoutException e) {
            return unconfirmed(issue, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return unconfirmed(issue, e);
        } catch (ExecutionException e) {
            return rejected(issue, e.getCause());
        } catch (JsonProcessingException | RuntimeException e) {
            return rejected(issue, e);
        }
    }

    private Outcome unconfirmed(CouponIssue issue, Throwable ex) {
        String error = describe(ex);
        log.warn("쿠폰 접수를 보냈는지 확인하지 못했다. producer 가 아직 쥐고 있어 예약은 남긴다: "
            + "campaignId={}, userId={}, issueNumber={}, error={}",
            issue.campaignId(), issue.userId(), issue.issueNumber(), error);
        count("unconfirmed", causeOf(ex));
        return new Outcome.Unconfirmed(error);
    }

    private Outcome rejected(CouponIssue issue, Throwable ex) {
        String error = describe(ex);
        log.warn("쿠폰 접수 메시지를 producer 가 포기했다. 예약을 지운다: campaignId={}, userId={}, issueNumber={}, error={}",
            issue.campaignId(), issue.userId(), issue.issueNumber(), error);
        count("rejected", causeOf(ex));
        return new Outcome.Rejected(error);
    }

    private static String describe(Throwable ex) {
        Throwable cause = NestedExceptionUtils.getMostSpecificCause(ex);
        return causeOf(ex) + ": " + cause.getMessage();
    }

    private static String causeOf(Throwable ex) {
        return NestedExceptionUtils.getMostSpecificCause(ex).getClass().getSimpleName();
    }

    private void count(String result, String cause) {
        meterRegistry.counter(METRIC, "result", result, "cause", cause).increment();
    }
}
