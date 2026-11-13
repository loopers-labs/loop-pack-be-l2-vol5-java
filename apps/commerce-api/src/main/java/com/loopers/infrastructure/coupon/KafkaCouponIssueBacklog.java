package com.loopers.infrastructure.coupon;

import com.loopers.config.CouponIssueListenerConfig;
import com.loopers.domain.coupon.CouponIssueBacklog;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.utils.Utils;
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
        TopicPartition partition = partition(campaignId);
        long end = endOffset(partition);
        return end == 0 || committed(partition) >= end;
    }

    @Override
    public long endOffset(Long campaignId) {
        return endOffset(partition(campaignId));
    }

    @Override
    public boolean isCommittedUpTo(Long campaignId, long offset) {
        return offset == 0 || committed(partition(campaignId)) >= offset;
    }

    private TopicPartition partition(Long campaignId) {
        int partitions = await(admin.describeTopics(List.of(topic)).allTopicNames()).get(topic).partitions().size();
        return new TopicPartition(topic, partitionOf(campaignId, partitions));
    }

    private long endOffset(TopicPartition partition) {
        return await(admin.listOffsets(Map.of(partition, OffsetSpec.latest())).all()).get(partition).offset();
    }

    private long committed(TopicPartition partition) {
        OffsetAndMetadata committed = await(admin.listConsumerGroupOffsets(CouponIssueListenerConfig.GROUP)
            .partitionsToOffsetAndMetadata()).get(partition);
        return committed == null ? 0 : committed.offset();
    }

    private static <T> T await(KafkaFuture<T> future) {
        try {
            return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("워커 lag 을 읽다 중단됐다", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("워커 lag 을 읽지 못했다", e);
        }
    }

    static int partitionOf(Long campaignId, int partitions) {
        return Utils.toPositive(Utils.murmur2(String.valueOf(campaignId).getBytes(StandardCharsets.UTF_8))) % partitions;
    }

    @PreDestroy
    void close() {
        admin.close();
    }
}
