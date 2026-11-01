package com.loopers.testsupport;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.ConsumerGroupState;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.errors.GroupIdNotFoundException;
import org.apache.kafka.common.serialization.StringDeserializer;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

/**
 * 사용자가 띄운 공유 로컬 Kafka 에서 테스트 실행이 소유한 topic·group 만 준비·정리한다. broker 를 띄우거나 멈추지 않는다.
 *
 * <p>bootstrap 은 시스템 속성 {@value #BOOTSTRAP_PROPERTY}, 환경 변수 {@value #BOOTSTRAP_ENV}, 기본 {@value #DEFAULT_BOOTSTRAP} 순이다.
 * 연결·준비 실패는 예외로 드러내며 skip 하거나 다른 broker 로 우회하지 않는다. 모든 대기에는 유한 deadline 을 둔다.
 * 정리는 이 객체가 만든 이름만 대상으로 하고, consumer·이를 쓰는 컨텍스트를 먼저 닫은 뒤 호출해야 한다.
 */
public final class LocalKafkaTestSupport implements AutoCloseable {

    public static final String BOOTSTRAP_PROPERTY = "test.kafka.bootstrap-servers";
    public static final String BOOTSTRAP_ENV = "TEST_KAFKA_BOOTSTRAP_SERVERS";
    public static final String DEFAULT_BOOTSTRAP = "localhost:19092";
    public static final int PARTITIONS = 2;

    private static final short REPLICATION_FACTOR = 1;
    private static final Duration DEADLINE = Duration.ofSeconds(30);
    /** 삭제 뒤 같은 이름이 다시 생기지 않는지 보는 구간. 자동 생성이 켜진 broker 에서 남은 client 의 재생성을 잡기 위함이다. */
    private static final Duration RECREATION_WATCH = Duration.ofSeconds(3);

    private final String bootstrapServers;
    private final String runId;
    private final Admin admin;
    private final Set<String> ownedTopics = new LinkedHashSet<>();
    private final Set<String> ownedGroups = new LinkedHashSet<>();
    private final AtomicInteger sequence = new AtomicInteger();

    private LocalKafkaTestSupport(String bootstrapServers, String runId, Admin admin) {
        this.bootstrapServers = bootstrapServers;
        this.runId = runId;
        this.admin = admin;
    }

    /** 공유 broker 에 AdminClient 로 연결하고 cluster 응답까지 확인한다. */
    public static LocalKafkaTestSupport connect() {
        String bootstrap = resolveBootstrapServers();
        String runId = LocalDateTime.now().format(DateTimeFormatter.ofPattern("MMddHHmmss")) + "-"
            + UUID.randomUUID().toString().substring(0, 8);
        Admin admin = Admin.create(Map.of(
            AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
            AdminClientConfig.CLIENT_ID_CONFIG, "local-kafka-test-" + runId,
            AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 10_000,
            AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, (int) DEADLINE.toMillis()));
        try {
            DescribeClusterResult cluster = admin.describeCluster();
            get(cluster.nodes());
            return new LocalKafkaTestSupport(bootstrap, runId, admin);
        } catch (RuntimeException failure) {
            admin.close(Duration.ofSeconds(5));
            throw new IllegalStateException("로컬 Kafka 연결 실패. 공유 Docker Kafka 실행·주소를 확인하세요. [bootstrap = " + bootstrap + "]",
                failure);
        }
    }

    static String resolveBootstrapServers() {
        String property = System.getProperty(BOOTSTRAP_PROPERTY);
        if (property != null && !property.isBlank()) {
            return property.trim();
        }
        String env = System.getenv(BOOTSTRAP_ENV);
        if (env != null && !env.isBlank()) {
            return env.trim();
        }
        return DEFAULT_BOOTSTRAP;
    }

    public String bootstrapServers() {
        return bootstrapServers;
    }

    public String runId() {
        return runId;
    }

    /** broker 수·id 와 이 검증이 기대는 broker 설정을 읽는다. 값을 바꾸지 않는다. */
    public BrokerInfo brokerInfo() {
        var nodes = get(admin.describeCluster().nodes());
        String clusterId = get(admin.describeCluster().clusterId());
        ConfigResource broker = new ConfigResource(ConfigResource.Type.BROKER, nodes.iterator().next().idString());
        Config config = get(admin.describeConfigs(List.of(broker)).all()).get(broker);
        return new BrokerInfo(clusterId, nodes.size(), value(config, "auto.create.topics.enable"),
            value(config, "min.insync.replicas"), value(config, "default.replication.factor"));
    }

    public record BrokerInfo(String clusterId, int brokerCount, String autoCreateTopics, String minInsyncReplicas,
                             String defaultReplicationFactor) {
    }

    /**
     * 실행 소유 topic 을 partition 2·복제 계수 1 로 만들고, 모든 partition 의 leader·빈 offset 을 확인한다.
     * 추가 설정이 없으면 acks=all 쓰기가 가능하도록 유효 min.insync.replicas 가 복제 계수 이하인지도 확인한다.
     */
    public String createTopic(String prefix, Map<String, String> configs) {
        String name = prefix + "-test-" + runId + "-" + sequence.incrementAndGet();
        ownedTopics.add(name);
        get(admin.createTopics(List.of(new NewTopic(name, PARTITIONS, REPLICATION_FACTOR).configs(configs))).all());
        await("topic 준비 " + name, () -> {
            TopicDescription description = describe(name);
            return description != null && description.partitions().size() == PARTITIONS
                && description.partitions().stream().allMatch(partition -> partition.leader() != null);
        });
        Map<TopicPartition, Long> begin = offsets(name, OffsetSpec.earliest());
        Map<TopicPartition, Long> end = offsets(name, OffsetSpec.latest());
        if (begin.size() != PARTITIONS || !begin.values().stream().allMatch(offset -> offset == 0L) || !begin.equals(end)) {
            throw new IllegalStateException("새 topic 의 offset 이 비어 있지 않습니다. [topic = " + name + "]");
        }
        if (configs.isEmpty() && Integer.parseInt(topicConfig(name, "min.insync.replicas")) > REPLICATION_FACTOR) {
            throw new IllegalStateException("min.insync.replicas 가 복제 계수보다 커서 acks=all 쓰기가 불가능합니다. [topic = " + name + "]");
        }
        return name;
    }

    public String createTopic(String prefix) {
        return createTopic(prefix, Map.of());
    }

    /** 실행 소유 group 이름을 등록한다. 실제 group 은 이 이름으로 구독한 consumer 가 만든다. */
    public String ownGroup(String prefix) {
        String name = prefix + "-test-" + runId + "-" + sequence.incrementAndGet();
        ownedGroups.add(name);
        return name;
    }

    public String topicConfig(String topic, String key) {
        ConfigResource resource = new ConfigResource(ConfigResource.Type.TOPIC, topic);
        return value(get(admin.describeConfigs(List.of(resource)).all()).get(resource), key);
    }

    public Map<TopicPartition, Long> beginningOffsets(String topic) {
        return offsets(topic, OffsetSpec.earliest());
    }

    public Map<TopicPartition, Long> endOffsets(String topic) {
        return offsets(topic, OffsetSpec.latest());
    }

    /** 소유 group 의 broker committed offset. consumer position·poll 결과가 아니라 broker 가 저장한 값이다. commit 이 없는 partition 은 빠진다. */
    public Map<TopicPartition, Long> committedOffsets(String group) {
        requireOwned(group);
        Map<TopicPartition, Long> committed = new HashMap<>();
        get(admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata()).forEach((partition, offset) -> {
            if (offset != null) {
                committed.put(partition, offset.offset());
            }
        });
        return committed;
    }

    /** 소유 group 의 broker 측 상태·member·배정. 값을 바꾸지 않는다. */
    public ConsumerGroupDescription describeGroup(String group) {
        requireOwned(group);
        return get(admin.describeConsumerGroups(List.of(group)).all()).get(group);
    }

    /** 직접 만드는 테스트 consumer 설정. 자동 commit·topic 자동 생성 요청을 끈 값을 실제 map 에 둔다. group 이 null 이면 assign 전용이다. */
    public Map<String, Object> consumerConfig(String group) {
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.CLIENT_ID_CONFIG, "local-kafka-test-consumer-" + runId + "-" + sequence.incrementAndGet());
        if (group != null) {
            requireOwned(group);
            config.put(ConsumerConfig.GROUP_ID_CONFIG, group);
        }
        return config;
    }

    private void requireOwned(String group) {
        if (!ownedGroups.contains(group)) {
            throw new IllegalArgumentException("이 실행이 소유하지 않은 group 입니다. [group = " + group + "]");
        }
    }

    /**
     * group 없이 각 partition 의 [from, to) 구간을 끝까지 읽는다. 모든 partition 의 position 이 to 에 닿아야 끝나며,
     * deadline 안에 닿지 못하면 실패한다. poll 이 비었다는 사실만으로 구간을 다 읽었다고 보지 않는다.
     */
    public List<ConsumerRecord<String, String>> readRange(Map<TopicPartition, Long> from, Map<TopicPartition, Long> to) {
        if (!from.keySet().equals(to.keySet())) {
            throw new IllegalArgumentException("시작·끝 partition 이 다릅니다.");
        }
        List<ConsumerRecord<String, String>> records = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerConfig(null))) {
            consumer.assign(from.keySet());
            from.forEach(consumer::seek);
            long deadline = System.nanoTime() + DEADLINE.toNanos();
            while (!from.keySet().stream().allMatch(partition -> consumer.position(partition) >= to.get(partition))) {
                if (System.nanoTime() > deadline) {
                    throw new IllegalStateException("offset 구간을 deadline 안에 끝까지 읽지 못했습니다. [to = " + to + "]");
                }
                consumer.poll(Duration.ofMillis(200)).forEach(record -> {
                    if (record.offset() < to.get(new TopicPartition(record.topic(), record.partition()))) {
                        records.add(record);
                    }
                });
            }
        }
        return records;
    }

    /** topic 전체를 현재 끝 offset 까지 읽는다. */
    public List<ConsumerRecord<String, String>> readAll(String topic) {
        return readRange(beginningOffsets(topic), endOffsets(topic));
    }

    /**
     * 소유 group 의 비활성을 확인해 삭제하고, 소유 topic 을 삭제한 뒤 삭제 완료·재생성 없음을 확인하고 AdminClient 를 닫는다.
     * 한 단계가 실패해도 나머지 정리를 시도하고 마지막에 첫 실패를 던진다.
     */
    @Override
    public void close() {
        List<RuntimeException> failures = new ArrayList<>();
        try {
            deleteOwnedGroups();
        } catch (RuntimeException failure) {
            failures.add(failure);
        }
        try {
            deleteOwnedTopics();
        } catch (RuntimeException failure) {
            failures.add(failure);
        }
        try {
            admin.close(Duration.ofSeconds(10));
        } catch (RuntimeException failure) {
            failures.add(failure);
        }
        if (!failures.isEmpty()) {
            RuntimeException first = failures.get(0);
            failures.stream().skip(1).forEach(first::addSuppressed);
            throw first;
        }
    }

    private void deleteOwnedGroups() {
        if (ownedGroups.isEmpty()) {
            return;
        }
        await("group 비활성 " + ownedGroups, () -> get(admin.describeConsumerGroups(ownedGroups).all()).values().stream()
            .allMatch(LocalKafkaTestSupport::inactive));
        Set<String> existing = get(admin.describeConsumerGroups(ownedGroups).all()).values().stream()
            .filter(group -> group.state() != ConsumerGroupState.DEAD)
            .map(ConsumerGroupDescription::groupId)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        for (String group : existing) {
            try {
                get(admin.deleteConsumerGroups(List.of(group)).all());
            } catch (IllegalStateException failure) {
                if (!(failure.getCause() instanceof GroupIdNotFoundException)) {
                    throw failure;
                }
            }
        }
        await("group 삭제 " + ownedGroups, () -> get(admin.describeConsumerGroups(ownedGroups).all()).values().stream()
            .allMatch(group -> group.state() == ConsumerGroupState.DEAD));
    }

    private static boolean inactive(ConsumerGroupDescription group) {
        return group.members().isEmpty()
            && (group.state() == ConsumerGroupState.EMPTY || group.state() == ConsumerGroupState.DEAD);
    }

    private void deleteOwnedTopics() {
        if (ownedTopics.isEmpty()) {
            return;
        }
        Set<String> existing = get(admin.listTopics().names()).stream()
            .filter(ownedTopics::contains)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!existing.isEmpty()) {
            get(admin.deleteTopics(existing).all());
        }
        await("topic 삭제 " + ownedTopics, () -> get(admin.listTopics().names()).stream().noneMatch(ownedTopics::contains));
        long watchUntil = System.nanoTime() + RECREATION_WATCH.toNanos();
        while (System.nanoTime() < watchUntil) {
            if (get(admin.listTopics().names()).stream().anyMatch(ownedTopics::contains)) {
                throw new IllegalStateException("삭제한 소유 topic 이 다시 생겼습니다. [topics = " + ownedTopics + "]");
            }
            sleep(Duration.ofMillis(300));
        }
    }

    private TopicDescription describe(String topic) {
        try {
            return admin.describeTopics(List.of(topic)).allTopicNames().get(10, TimeUnit.SECONDS).get(topic);
        } catch (ExecutionException missing) {
            return null;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        } catch (TimeoutException timeout) {
            throw new IllegalStateException("topic 조회 timeout. [topic = " + topic + "]", timeout);
        }
    }

    private Map<TopicPartition, Long> offsets(String topic, OffsetSpec spec) {
        Map<TopicPartition, OffsetSpec> request = new HashMap<>();
        for (int partition = 0; partition < PARTITIONS; partition++) {
            request.put(new TopicPartition(topic, partition), spec);
        }
        return get(admin.listOffsets(request).all()).entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().offset()));
    }

    private static String value(Config config, String key) {
        var entry = config.get(key);
        return entry == null ? null : entry.value();
    }

    private static void await(String what, BooleanSupplier condition) {
        long deadline = System.nanoTime() + DEADLINE.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException(what + " 가 deadline(" + DEADLINE.toSeconds() + "초) 안에 끝나지 않았습니다.");
            }
            sleep(Duration.ofMillis(200));
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private static <T> T get(KafkaFuture<T> future) {
        try {
            return future.get(DEADLINE.toSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        } catch (ExecutionException failure) {
            throw new IllegalStateException("Kafka 관리 요청 실패: " + failure.getCause().getClass().getName(), failure.getCause());
        } catch (TimeoutException timeout) {
            throw new IllegalStateException("Kafka 관리 요청이 deadline 안에 끝나지 않았습니다.", timeout);
        }
    }
}
