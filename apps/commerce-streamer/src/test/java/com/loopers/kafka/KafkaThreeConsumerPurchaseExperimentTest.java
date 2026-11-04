package com.loopers.kafka;

import com.loopers.domain.purchase.ProductPurchaseActivity;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.common.ConsumerGroupState;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.GroupIdNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.CommonContainerStoppingErrorHandler;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ConsumerAwareMessageListener;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaThreeConsumerPurchaseExperimentTest extends KafkaPurchaseExperimentSupport {
    @Test
    @Timeout(110)
    void threeMembersSavePurchasesAndResumeWithTheSelectedGroup() throws Exception {
        String topic = "three-partition-purchases-" + UUID.randomUUID();
        // 격리 broker에서 선택한 업무용 group 이름 자체를 검증한다.
        String group = "purchase-activity";
        Set<String> deliveries = ConcurrentHashMap.newKeySet();
        List<String> duplicateDeliveries = new java.util.concurrent.CopyOnWriteArrayList<>();
        Map<Integer, Set<String>> membersByPartition = new ConcurrentHashMap<>();
        ConsumerAwareMessageListener<String, byte[]> listener = (record, consumer) -> {
            if (!deliveries.add(record.partition() + ":" + record.offset())) {
                duplicateDeliveries.add(record.partition() + ":" + record.offset());
            }
            membersByPartition.computeIfAbsent(record.partition(), ignored -> ConcurrentHashMap.newKeySet())
                .add(consumer.groupMetadata().memberId());
            adapter.handle(record);
        };

        try (var admin = admin(); var producer = producer()) {
            admin.createTopics(List.of(new NewTopic(topic, 3, (short) 1))).all().get(10, TimeUnit.SECONDS);
            var first = parent(topic, group, listener);
            Map<Integer, Integer> produced = new TreeMap<>();
            Map<String, List<Integer>> stable;
            List<Long> originalRowIds;
            try {
                first.start();
                await(() -> stableAssignments(admin, group, topic, first).size() == 3);
                stable = stableAssignments(admin, group, topic, first);
                assertThat(stable.values()).allSatisfy(partitions -> assertThat(partitions).hasSize(1));
                for (long orderId = 1_000; orderId < 1_030; orderId++) {
                    var metadata = sendOrder(producer, topic, orderId);
                    produced.merge(metadata.partition(), 1, Integer::sum);
                }
                assertThat(produced.keySet()).containsExactlyInAnyOrder(0, 1, 2);
                Map<TopicPartition, Long> ends = endOffsets(admin, topic);
                await(() -> committedEquals(admin, group, ends) && confirmations.count() == 30);
                assertOrders(LongStream.range(1_000, 1_030).toArray());
                assertThat(deliveries).hasSize(30);
                assertThat(duplicateDeliveries).isEmpty();
                assertThat(membersByPartition).hasSize(3);
                membersByPartition.forEach((partition, members) -> {
                    assertThat(members).hasSize(1);
                    String owner = stable.entrySet().stream().filter(entry -> entry.getValue().contains(partition))
                        .findFirst().orElseThrow().getKey();
                    assertThat(members).containsExactly(owner);
                });
                originalRowIds = activities.findAll().stream().map(ProductPurchaseActivity::getId).sorted().toList();
            } finally {
                first.stop();
            }

            var resumed = parent(topic, group, listener);
            try {
                resumed.start();
                await(() -> stableAssignments(admin, group, topic, resumed).size() == 3);
                sendOrder(producer, topic, 2_000L);
                Map<TopicPartition, Long> ends = endOffsets(admin, topic);
                await(() -> committedEquals(admin, group, ends) && confirmations.count() == 31);
                long[] expected = LongStream.concat(LongStream.range(1_000, 1_030), LongStream.of(2_000)).toArray();
                assertOrders(expected);
                assertThat(deliveries).hasSize(31);
                assertThat(duplicateDeliveries).isEmpty();
                assertThat(activities.findAll().stream().filter(row -> row.getOrderId() < 1_030L)
                    .map(ProductPurchaseActivity::getId).sorted().toList()).isEqualTo(originalRowIds);
                report("THREE_CONSUMER_PURCHASE_EXPERIMENT", Map.of("group", group,
                    "firstPhaseAssignments", stable, "firstPhaseRecordsByPartition", produced,
                    "initialOrders", 30, "ordersAfterRestart", confirmations.count(),
                    "activityRows", activities.count(), "uniqueDeliveredRecords", deliveries.size(),
                    "duplicates", duplicateDeliveries.size(), "existingRowIdsUnchanged", true));
            } finally {
                resumed.stop();
            }
        }
    }

    private ConcurrentMessageListenerContainer<String, byte[]> parent(
        String topic, String group, ConsumerAwareMessageListener<String, byte[]> listener
    ) {
        var properties = properties(topic, group, listener);
        assertThat(properties.getGroupId()).isEqualTo("purchase-activity");
        assertThat(consumerSettings().get("auto.offset.reset")).isEqualTo("earliest");
        var parent = new ConcurrentMessageListenerContainer<>(
            new DefaultKafkaConsumerFactory<String, byte[]>(consumerSettings()), properties);
        parent.setConcurrency(3);
        parent.setBeanName("three-consumer-purchase-experiment");
        parent.setCommonErrorHandler(new CommonContainerStoppingErrorHandler());
        return parent;
    }

    private Map<String, List<Integer>> stableAssignments(AdminClient admin, String group, String topic,
        ConcurrentMessageListenerContainer<String, byte[]> container) throws Exception {
        ConsumerGroupDescription description;
        try {
            description = admin.describeConsumerGroups(List.of(group)).describedGroups().get(group)
                .get(5, TimeUnit.SECONDS);
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof GroupIdNotFoundException) {
                return Map.of();
            }
            throw exception;
        }
        if (description.state() != ConsumerGroupState.STABLE || description.members().size() != 3) {
            return Map.of();
        }
        Map<String, List<Integer>> result = new TreeMap<>();
        description.members().forEach(member -> result.put(member.consumerId(),
            member.assignment().topicPartitions().stream().filter(p -> p.topic().equals(topic))
                .map(TopicPartition::partition).sorted().toList()));
        List<Integer> local = new ArrayList<>();
        container.getContainers().forEach(child -> {
            var assigned = child.getAssignedPartitions();
            if (assigned != null) {
                assigned.forEach(partition -> local.add(partition.partition()));
            }
        });
        if (!local.stream().sorted().toList().equals(List.of(0, 1, 2))) {
            return Map.of();
        }
        return result;
    }

    private Map<TopicPartition, Long> endOffsets(AdminClient admin, String topic) throws Exception {
        Map<TopicPartition, OffsetSpec> request = new HashMap<>();
        for (int partition = 0; partition < 3; partition++) {
            request.put(new TopicPartition(topic, partition), OffsetSpec.latest());
        }
        Map<TopicPartition, Long> result = new HashMap<>();
        admin.listOffsets(request).all().get(10, TimeUnit.SECONDS)
            .forEach((partition, info) -> result.put(partition, info.offset()));
        return result;
    }

    private boolean committedEquals(AdminClient admin, String group, Map<TopicPartition, Long> expected)
        throws Exception {
        var committed = admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata()
            .get(10, TimeUnit.SECONDS);
        return expected.entrySet().stream().allMatch(entry -> committed.get(entry.getKey()) != null
            && committed.get(entry.getKey()).offset() == entry.getValue());
    }
}
