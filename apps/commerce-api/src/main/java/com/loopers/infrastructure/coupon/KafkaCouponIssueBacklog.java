package com.loopers.infrastructure.coupon;

import com.loopers.config.CouponIssueListenerConfig;
import com.loopers.domain.coupon.CouponIssueBacklog;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;

import java.util.HashMap;
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
        return isCommittedUpTo(position());
    }

    @Override
    public Position position() {
        Map<TopicPartition, OffsetSpec> latest = partitions().stream()
            .collect(Collectors.toMap(Function.identity(), partition -> OffsetSpec.latest()));
        Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> ends = await(admin.listOffsets(latest).all());
        Map<Integer, Long> endOffsets = new HashMap<>();
        ends.forEach((partition, info) -> endOffsets.put(partition.partition(), info.offset()));
        return new Position(endOffsets);
    }

    @Override
    public boolean isCommittedUpTo(Position position) {
        Map<TopicPartition, OffsetAndMetadata> committed = await(
            admin.listConsumerGroupOffsets(CouponIssueListenerConfig.GROUP).partitionsToOffsetAndMetadata());
        return position.endOffsets().entrySet().stream().allMatch(entry -> {
            OffsetAndMetadata offset = committed.get(new TopicPartition(topic, entry.getKey()));
            return entry.getValue() == 0 || (offset != null && offset.offset() >= entry.getValue());
        });
    }

    private List<TopicPartition> partitions() {
        return await(admin.describeTopics(List.of(topic)).allTopicNames()).get(topic).partitions().stream()
            .map(info -> new TopicPartition(topic, info.partition()))
            .toList();
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

    @PreDestroy
    void close() {
        admin.close();
    }
}
