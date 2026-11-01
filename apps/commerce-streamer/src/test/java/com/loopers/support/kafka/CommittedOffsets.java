package com.loopers.support.kafka;

import com.loopers.testcontainers.KafkaTestContainersConfig;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/** broker 에 commit 된 group 의 다음 읽기 위치를 조회한다. */
public final class CommittedOffsets {

    private CommittedOffsets() {
    }

    /** commit 한 적이 없으면 null 이다. */
    public static Long of(String group, TopicPartition partition) {
        try (AdminClient admin = AdminClient.create(
            Map.<String, Object>of("bootstrap.servers", KafkaTestContainersConfig.bootstrapServers()))) {
            OffsetAndMetadata committed = admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata()
                .get(10, TimeUnit.SECONDS).get(partition);
            return committed == null ? null : committed.offset();
        } catch (Exception e) {
            throw new IllegalStateException("committed offset 을 조회하지 못했습니다.", e);
        }
    }
}
