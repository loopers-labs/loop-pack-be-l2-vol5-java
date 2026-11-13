package com.loopers.infrastructure.coupon;

import com.loopers.config.CouponIssueListenerConfig;
import com.loopers.domain.coupon.CouponIssueBacklog;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.internals.BuiltInPartitioner;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
class KafkaCouponIssueBacklog implements CouponIssueBacklog {

    private static final long TIMEOUT_SECONDS = 10;

    private final Admin admin;
    private final String topic;

    KafkaCouponIssueBacklog(KafkaAdmin kafkaAdmin, @Value("${kafka.topics.coupon-issues}") String topic) {
        this.admin = Admin.create(kafkaAdmin.getConfigurationProperties());
        this.topic = topic;
    }

    @Override
    public boolean isDrained(Long campaignId) {
        try {
            int partitions = admin.describeTopics(List.of(topic)).allTopicNames()
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS).get(topic).partitions().size();
            TopicPartition partition = new TopicPartition(topic, partitionOf(campaignId, partitions));
            long end = admin.listOffsets(Map.of(partition, OffsetSpec.latest())).all()
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS).get(partition).offset();
            OffsetAndMetadata committed = admin.listConsumerGroupOffsets(CouponIssueListenerConfig.GROUP)
                .partitionsToOffsetAndMetadata().get(TIMEOUT_SECONDS, TimeUnit.SECONDS).get(partition);
            return end == 0 || (committed != null && committed.offset() >= end);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("워커 lag 을 읽다 중단됐다", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("워커 lag 을 읽지 못했다", e);
        }
    }

    static int partitionOf(Long campaignId, int partitions) {
        return BuiltInPartitioner.partitionForKey(String.valueOf(campaignId).getBytes(StandardCharsets.UTF_8), partitions);
    }

    @PreDestroy
    void close() {
        admin.close();
    }
}
