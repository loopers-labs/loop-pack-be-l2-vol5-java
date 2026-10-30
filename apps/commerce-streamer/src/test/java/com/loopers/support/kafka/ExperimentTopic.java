package com.loopers.support.kafka;

import com.loopers.testcontainers.KafkaTestContainersConfig;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.ConsumerGroupState;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 실험 하나가 쓰는 고유한 topic 과 group. 다른 실행의 topic·group 과 섞이지 않고, producer·consumer·admin 이 모두 테스트
 * broker 의 실제 주소를 쓴다.
 */
public final class ExperimentTopic {

    private static final Duration DEADLINE = Duration.ofSeconds(60);
    private static final Duration POLL = Duration.ofMillis(200);

    private final String name;
    private final String group;
    private final int partitions;

    private ExperimentTopic(String name, String group, int partitions) {
        this.name = name;
        this.group = group;
        this.partitions = partitions;
    }

    public static ExperimentTopic create(String purpose, int partitions) {
        String id = UUID.randomUUID().toString();
        ExperimentTopic topic = new ExperimentTopic("delivery-" + purpose + "-" + id, "delivery-" + purpose + "-group-" + id, partitions);
        try (AdminClient admin = admin()) {
            admin.createTopics(List.of(new NewTopic(topic.name, partitions, (short) 1))).all().get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("topic 을 만들지 못했습니다: " + topic.name, e);
        }
        return topic;
    }

    public String name() {
        return name;
    }

    // ---- 보내기 ----

    /** partition 을 producer 에게 맡기고 보낸다. 실제로 간 partition·offset 이 metadata 에 담긴다. */
    public RecordMetadata send(String key, String value) {
        return send(new ProducerRecord<>(name, key, bytes(value)));
    }

    /** partition 을 명시해 보낸다. */
    public RecordMetadata send(int partition, String key, String value) {
        return send(new ProducerRecord<>(name, partition, key, bytes(value)));
    }

    private RecordMetadata send(ProducerRecord<String, byte[]> record) {
        try (KafkaProducer<String, byte[]> producer = new KafkaProducer<>(Map.<String, Object>of(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestContainersConfig.bootstrapServers(),
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class
        ))) {
            return producer.send(record).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("record 를 보내지 못했습니다.", e);
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    // ---- 읽는 쪽 ----

    /** 이 group 에 참여해 계속 poll 하는 member 를 추가한다. */
    public GroupMember join(String clientId) {
        return new GroupMember(this, clientId);
    }

    /** 테스트가 poll·commit 을 직접 지휘하는 consumer. offsetReset 은 committed 위치가 없을 때의 동작이다. */
    public ControlledConsumer controlledConsumer(String clientId, String offsetReset) {
        return new ControlledConsumer(this, clientId, offsetReset);
    }

    Map<String, Object> consumerProps(String clientId, String offsetReset) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestContainersConfig.bootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, group);
        props.put(ConsumerConfig.CLIENT_ID_CONFIG, clientId);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, offsetReset);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        return props;
    }

    // ---- broker 가 알려 주는 사실 ----

    /** 배정이 목표 모양({@link GroupAssignment#partitionCounts()})에 도달하고 모든 partition 이 배정될 때까지 유한하게 기다린다. */
    public GroupAssignment awaitAssignment(List<Integer> expectedPartitionCounts) {
        Set<Integer> allPartitions = IntStream.range(0, partitions).boxed().collect(Collectors.toCollection(TreeSet::new));
        await().atMost(DEADLINE).pollInterval(POLL).untilAsserted(() -> {
            GroupAssignment assignment = assignment();
            assertThat(assignment.partitionCounts()).isEqualTo(expectedPartitionCounts);
            assertThat(assignment.assignedPartitions()).isEqualTo(allPartitions);
        });
        return assignment();
    }

    /** broker 가 알려 주는 실제 group 배정. 안정(STABLE)된 뒤의 값만 돌려준다. */
    public GroupAssignment assignment() {
        try (AdminClient admin = admin()) {
            ConsumerGroupDescription description = admin.describeConsumerGroups(List.of(group))
                .describedGroups().get(group).get(10, TimeUnit.SECONDS);
            Map<String, Set<Integer>> partitionsByMember = new TreeMap<>();
            if (description.state() == ConsumerGroupState.STABLE) {
                description.members().forEach(member -> partitionsByMember.put(
                    member.clientId(),
                    member.assignment().topicPartitions().stream()
                        .filter(tp -> tp.topic().equals(name)).map(TopicPartition::partition)
                        .collect(Collectors.toCollection(TreeSet::new))
                ));
            }
            return new GroupAssignment(partitionsByMember);
        } catch (Exception e) {
            throw new IllegalStateException("group 배정을 조회하지 못했습니다.", e);
        }
    }

    /** group 이 재시작 시 이어 갈 위치. commit 한 적이 없으면 null 이다. */
    public Long committedOffset(TopicPartition partition) {
        try (AdminClient admin = admin()) {
            OffsetAndMetadata committed = admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata()
                .get(10, TimeUnit.SECONDS).get(partition);
            return committed == null ? null : committed.offset();
        } catch (Exception e) {
            throw new IllegalStateException("committed offset 을 조회하지 못했습니다.", e);
        }
    }

    private static AdminClient admin() {
        return AdminClient.create(Map.<String, Object>of("bootstrap.servers", KafkaTestContainersConfig.bootstrapServers()));
    }
}
