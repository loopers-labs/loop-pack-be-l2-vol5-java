package com.loopers.support.kafka;

import com.loopers.testcontainers.KafkaBrokerFixture;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.MemberDescription;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

/**
 * 전달 실험(KafkaDeliveryTest들)이 공유하는 도구. producer·consumer·admin은 모두 {@link KafkaBrokerFixture#bootstrapServers()} 하나를 쓴다.
 * broker에 물어보는 값(배정·committed offset)은 테스트 로그가 아니라 broker의 답이다.
 */
public final class KafkaLab {

    private static final long ADMIN_TIMEOUT_SECONDS = 30;

    private KafkaLab() {
    }

    public static KafkaProducer<String, String> stringProducer() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaBrokerFixture.bootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 10_000);
        return new KafkaProducer<>(props);
    }

    /** String key·value consumer 설정. 자동 commit은 끄고, auto.offset.reset은 호출자가 정한다. */
    public static Map<String, Object> consumerProps(String groupId, String clientId, String autoOffsetReset) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaBrokerFixture.bootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.CLIENT_ID_CONFIG, clientId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, autoOffsetReset);
        return props;
    }

    /** broker가 본 group의 지금 배정: clientId → 배정된 partition 번호들. group에 member가 없으면 비어 있다. */
    public static Map<String, Set<Integer>> assignmentsOf(String groupId, String topic) {
        try (AdminClient admin = admin()) {
            ConsumerGroupDescription group = admin.describeConsumerGroups(java.util.List.of(groupId))
                .describedGroups().get(groupId).get(ADMIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            Map<String, Set<Integer>> assignments = new TreeMap<>();
            for (MemberDescription member : group.members()) {
                Set<Integer> partitions = new TreeSet<>();
                for (TopicPartition tp : member.assignment().topicPartitions()) {
                    if (tp.topic().equals(topic)) {
                        partitions.add(tp.partition());
                    }
                }
                assignments.put(member.clientId(), partitions);
            }
            return assignments;
        } catch (Exception e) {
            throw new IllegalStateException("group 배정을 조회하지 못했습니다: " + groupId, e);
        }
    }

    /** broker에 commit된 group의 offset. 아직 commit한 적이 없으면 null이다. */
    public static Long committedOffset(String groupId, TopicPartition partition) {
        try (AdminClient admin = admin()) {
            Map<TopicPartition, OffsetAndMetadata> offsets = admin.listConsumerGroupOffsets(groupId)
                .partitionsToOffsetAndMetadata().get(ADMIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            OffsetAndMetadata committed = offsets.get(partition);
            return committed == null ? null : committed.offset();
        } catch (Exception e) {
            throw new IllegalStateException("committed offset을 조회하지 못했습니다: " + groupId + " " + partition, e);
        }
    }

    private static AdminClient admin() {
        return AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaBrokerFixture.bootstrapServers()));
    }
}
