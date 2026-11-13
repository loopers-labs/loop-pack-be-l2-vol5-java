package com.loopers.infrastructure.coupon;

import com.loopers.config.CouponIssueListenerConfig;
import com.loopers.domain.coupon.CouponIssueBacklog;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.stream.Collectors;

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
    public boolean isDrained() {
        try {
            TopicDescription description = admin.describeTopics(List.of(topic)).allTopicNames()
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS).get(topic);
            List<TopicPartition> partitions = description.partitions().stream()
                .map(info -> new TopicPartition(topic, info.partition()))
                .toList();
            Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> ends = admin.listOffsets(
                partitions.stream().collect(Collectors.toMap(Function.identity(), partition -> OffsetSpec.latest())))
                .all().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            Map<TopicPartition, OffsetAndMetadata> committed = admin
                .listConsumerGroupOffsets(CouponIssueListenerConfig.GROUP)
                .partitionsToOffsetAndMetadata().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return partitions.stream().allMatch(partition -> {
                long end = ends.get(partition).offset();
                OffsetAndMetadata offset = committed.get(partition);
                return end == 0 || (offset != null && offset.offset() >= end);
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("워커 lag 을 읽다 중단됐다", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("워커 lag 을 읽지 못했다", e);
        }
    }

    @PreDestroy
    void close() {
        admin.close();
    }
}
