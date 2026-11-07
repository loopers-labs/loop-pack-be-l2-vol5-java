package com.loopers.e2e;

import com.loopers.support.KafkaTestRecords;
import com.loopers.testcontainers.KafkaTestContainersConfig;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** broker 관찰. 기록은 group 없이 직접 읽고(KafkaTestRecords), 소비 진행은 group 의 committed offset 으로 본다. 대기는 모두 deadline 이 있다. */
public final class E2eKafka {
    public static final Duration TIMEOUT = Duration.ofSeconds(60);

    private E2eKafka() {
    }

    /** 그 eventId 를 담은 record, partition·offset 순. */
    public static List<ConsumerRecord<String, String>> recordsOf(String topic, String eventId) {
        return KafkaTestRecords.readAll(topic).stream()
            .filter(record -> record.value().contains("\"eventId\":\"" + eventId + "\""))
            .toList();
    }

    public static Map<TopicPartition, Long> endOffsets(String topic) {
        return KafkaTestRecords.endOffsets(topic);
    }

    /** group 의 partition 별 committed offset (commit 이 없는 partition 은 0). */
    public static Map<TopicPartition, Long> committed(String groupId, String topic) {
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestContainersConfig.bootstrapServers()))) {
            Map<TopicPartition, OffsetAndMetadata> offsets = admin.listConsumerGroupOffsets(groupId)
                .partitionsToOffsetAndMetadata().get(10, TimeUnit.SECONDS);
            return endOffsets(topic).keySet().stream().collect(Collectors.toMap(
                tp -> tp, tp -> offsets.get(tp) == null ? 0L : offsets.get(tp).offset()));
        } catch (Exception e) {
            throw new IllegalStateException("committed offset 조회 실패 [group = " + groupId + "]", e);
        }
    }

    /** consumer 가 지금 기록된 record 를 모두 처리하고 ack 했다 (committed = log end). */
    public static void awaitConsumedAll(String groupId, String topic) {
        await("group " + groupId + " 가 " + topic + " 끝까지 commit",
            () -> committed(groupId, topic).equals(endOffsets(topic)),
            () -> "committed = " + committed(groupId, topic) + ", end = " + endOffsets(topic));
    }

    public static void await(String what, BooleanSupplier condition, Supplier<String> state) {
        Instant deadline = Instant.now().plus(TIMEOUT);
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("대기 초과 (" + TIMEOUT + "): " + what + " — 현재 상태: " + state.get());
            }
            try {
                TimeUnit.MILLISECONDS.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("대기 중 interrupt: " + what, e);
            }
        }
    }
}
