package com.loopers.infrastructure.coupon.messaging;

import com.loopers.domain.coupon.gateway.CouponIssueRequestLog;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/** 접수 consumer group 의 처리 위치가 topic 끝에 닿았는지 본다. 재구성 전에 접수 기록이 DB 에 다 모였는지 확인한다 (8주차 D-140, D-174) */
@Slf4j
@Component
public class KafkaCouponIssueRequestLog implements CouponIssueRequestLog {

    private static final long TIMEOUT_SECONDS = 5;

    private final AdminClient admin;
    private final String topic;
    private final String group;

    public KafkaCouponIssueRequestLog(
        KafkaProperties kafkaProperties,
        @Value("${coupon-issue.topic}") String topic,
        @Value("${coupon-issue.consumer-group}") String group
    ) {
        this.admin = AdminClient.create(kafkaProperties.buildAdminProperties(null));
        this.topic = topic;
        this.group = group;
    }

    @Override
    public boolean caughtUp() {
        try {
            Map<TopicPartition, OffsetSpec> latest = admin.describeTopics(List.of(topic)).allTopicNames()
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS).get(topic).partitions().stream()
                .collect(Collectors.toMap(info -> new TopicPartition(topic, info.partition()), info -> OffsetSpec.latest()));
            Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> end = admin.listOffsets(latest).all()
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            Map<TopicPartition, OffsetAndMetadata> committed = admin.listConsumerGroupOffsets(group)
                .partitionsToOffsetAndMetadata().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return end.entrySet().stream().allMatch(entry -> {
                OffsetAndMetadata position = committed.get(entry.getKey());
                long processed = position == null ? 0L : position.offset();
                return processed >= entry.getValue().offset();
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            log.warn("접수 consumer 처리 위치를 확인하지 못함. 재구성을 미룸. cause={}", e.toString());
            return false;
        }
    }

    @PreDestroy
    void close() {
        admin.close();
    }
}
