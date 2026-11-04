package com.loopers.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.testcontainers.KafkaTestBroker;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.CommitFailedException;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.KafkaMessageListenerContainer;
import org.springframework.kafka.listener.MessageListener;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 블로그에 설명으로만 있던 consumer 설정을 실제 broker에서 확인한다. 업무 코드·DB 없이 설정의 동작만 본다.
 * <p>
 * 실행: {@code ./gradlew :apps:commerce-streamer:experimentTest --tests '*KafkaConsumerSettingsExperimentTest'}
 */
@Tag("experiment")
class KafkaConsumerSettingsExperimentTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static KafkaTestBroker broker;
    private static AdminClient admin;

    @BeforeAll
    static void start() {
        broker = new KafkaTestBroker();
        admin = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, broker.bootstrapServers()));
    }

    @AfterAll
    static void stop() {
        if (admin != null) {
            admin.close();
        }
        if (broker != null) {
            broker.close();
        }
    }

    @Test
    @Timeout(120)
    void autoCommitRecordsThePolledPositionWithoutLookingAtTheProcessingResult() throws Exception {
        Map<String, Object> enabled = pollThenCloseWithoutProcessing(true);
        Map<String, Object> disabled = pollThenCloseWithoutProcessing(false);
        System.out.println("AUTO_COMMIT_EXPERIMENT " + JSON.writeValueAsString(List.of(enabled, disabled)));

        assertThat(enabled.get("committedAfterClose")).isEqualTo(3L);
        assertThat(enabled.get("readAfterRestart")).isEqualTo(0);
        assertThat(disabled.get("committedAfterClose")).isNull();
        assertThat(disabled.get("readAfterRestart")).isEqualTo(3);
    }

    @Test
    @Timeout(120)
    void killedConsumerIsRemovedAfterSessionTimeoutAndAutoCommitDoesNotRunWithoutClose() throws Exception {
        String topic = createTopic(1);
        String group = "killed-" + UUID.randomUUID();
        send(topic, 3);

        Process child = childConsumer(topic, group, true);
        try (var output = new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {
            String line = output.readLine();
            assertThat(line).startsWith("READY");
            assertThat(members(group)).isEqualTo(1);

            long killedAt = System.nanoTime();
            child.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
            Thread.sleep(2_000);
            int membersTwoSecondsAfterKill = members(group);
            await(() -> members(group) == 0, "broker removes the killed consumer");
            long removedAfterMs = Duration.ofNanos(System.nanoTime() - killedAt).toMillis();

            OffsetAndMetadata committed = committed(group, new TopicPartition(topic, 0));
            int readAfterRestart = readAll(topic, group).size();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("sessionTimeoutMs", 6_000);
            result.put("membersTwoSecondsAfterKill", membersTwoSecondsAfterKill);
            result.put("removedAfterKillMs", removedAfterMs);
            result.put("committedAfterKill", committed == null ? null : committed.offset());
            result.put("readAfterRestart", readAfterRestart);
            System.out.println("SESSION_TIMEOUT_EXPERIMENT " + JSON.writeValueAsString(result));

            assertThat(membersTwoSecondsAfterKill).isEqualTo(1);
            assertThat(removedAfterMs).isGreaterThan(3_000L);
            assertThat(committed).isNull();
            assertThat(readAfterRestart).isEqualTo(3);
        } finally {
            child.destroyForcibly();
        }
    }

    @Test
    @Timeout(120)
    void normalCloseLeavesTheGroupImmediately() throws Exception {
        String topic = createTopic(1);
        String group = "closed-" + UUID.randomUUID();
        send(topic, 1);
        try (var consumer = consumer(group, false, Map.of(
            ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 6_000, ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 1_000))) {
            consumer.subscribe(List.of(topic));
            await(() -> consumer.poll(Duration.ofMillis(200)).count() > 0, "first record");
        }
        long closedAt = System.nanoTime();
        await(() -> members(group) == 0, "closed consumer leaves");
        long leftAfterMs = Duration.ofNanos(System.nanoTime() - closedAt).toMillis();
        System.out.println("NORMAL_CLOSE_EXPERIMENT " + JSON.writeValueAsString(Map.of("leftAfterCloseMs", leftAfterMs)));
        assertThat(leftAfterMs).isLessThan(3_000L);
    }

    @Test
    @Timeout(120)
    void slowProcessingKeepsHeartbeatButLeavesTheGroupAfterMaxPollInterval() throws Exception {
        String topic = createTopic(1);
        String group = "slow-" + UUID.randomUUID();
        TopicPartition partition = new TopicPartition(topic, 0);
        send(topic, 1);

        Map<String, Object> result = new LinkedHashMap<>();
        // session 6초보다 긴 12초를 poll 간격으로 둔다. heartbeat가 멈춘다면 6초 뒤에 이미 빠져야 한다.
        try (var slow = consumer(group, false, Map.of(
            ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 6_000,
            ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 1_000,
            ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 12_000))) {
            slow.subscribe(List.of(topic));
            await(() -> slow.poll(Duration.ofMillis(200)).count() > 0, "slow consumer receives the record");
            long lastPoll = System.nanoTime();

            Thread.sleep(9_000);
            result.put("membersAt9sWithoutPoll", members(group));

            await(() -> members(group) == 0, "slow consumer leaves after max.poll.interval.ms");
            result.put("leftAfterLastPollMs", Duration.ofNanos(System.nanoTime() - lastPoll).toMillis());

            Throwable commitFailure = null;
            try {
                slow.commitSync(Map.of(partition, new OffsetAndMetadata(1)));
            } catch (CommitFailedException failure) {
                commitFailure = failure;
            }
            result.put("lateCommitFailure", commitFailure == null ? null : commitFailure.getClass().getSimpleName());
        }
        result.put("readByNextConsumer", readAll(topic, group).size());
        System.out.println("MAX_POLL_INTERVAL_EXPERIMENT " + JSON.writeValueAsString(result));

        assertThat(result.get("membersAt9sWithoutPoll")).isEqualTo(1);
        assertThat((long) result.get("leftAfterLastPollMs")).isGreaterThanOrEqualTo(11_000L);
        assertThat(result.get("lateCommitFailure")).isEqualTo("CommitFailedException");
        assertThat(result.get("readByNextConsumer")).isEqualTo(1);
    }

    @Test
    @Timeout(120)
    void maxPollRecordsLimitsOnePollNotRecordsPerSecond() throws Exception {
        int total = 3_000;
        List<Map<String, Object>> results = new ArrayList<>();
        for (int limit : List.of(100, 500)) {
            String topic = createTopic(1);
            send(topic, total);
            try (var consumer = consumer("poll-" + limit + "-" + UUID.randomUUID(), false, Map.of(
                ConsumerConfig.MAX_POLL_RECORDS_CONFIG, limit))) {
                consumer.subscribe(List.of(topic));
                int read = 0;
                int polls = 0;
                int largest = 0;
                long start = 0;
                while (read < total) {
                    int count = consumer.poll(Duration.ofMillis(200)).count();
                    if (count == 0) {
                        continue;
                    }
                    start = start == 0 ? System.nanoTime() : start;
                    read += count;
                    polls++;
                    largest = Math.max(largest, count);
                }
                long elapsedMs = Math.max(1, Duration.ofNanos(System.nanoTime() - start).toMillis());
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("maxPollRecords", limit);
                result.put("polls", polls);
                result.put("largestPoll", largest);
                result.put("elapsedMs", elapsedMs);
                result.put("recordsPerSecond", total * 1000L / elapsedMs);
                results.add(result);
                assertThat(largest).isLessThanOrEqualTo(limit);
            }
        }
        System.out.println("MAX_POLL_RECORDS_EXPERIMENT " + JSON.writeValueAsString(results));
        assertThat((long) results.getFirst().get("recordsPerSecond")).isGreaterThan(100L);
    }

    @Test
    @Timeout(120)
    void staticMemberRestartingWithinSessionDoesNotRebalanceOthers() throws Exception {
        Map<String, Object> dynamicMember = restartOneOfTwoConsumers(null);
        Map<String, Object> staticMember = restartOneOfTwoConsumers("purchase-consumer-1");
        System.out.println("STATIC_MEMBERSHIP_EXPERIMENT " + JSON.writeValueAsString(List.of(dynamicMember, staticMember)));

        assertThat((int) dynamicMember.get("rebalancesSeenByOther")).isPositive();
        assertThat(staticMember.get("membersAfterClose")).isEqualTo(2);
        assertThat(staticMember.get("rebalancesSeenByOther")).isEqualTo(0);
    }

    @Test
    void deliveryTimeoutShorterThanLingerPlusRequestTimeoutFailsAtStartup() {
        Map<String, Object> config = Map.of(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9",
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
            ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 20_000,
            ProducerConfig.LINGER_MS_CONFIG, 0,
            ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 15_000);
        assertThatThrownBy(() -> new KafkaProducer<String, String>(config).close())
            .hasRootCauseInstanceOf(org.apache.kafka.common.config.ConfigException.class)
            .hasRootCauseMessage("delivery.timeout.ms should be equal to or larger than linger.ms + request.timeout.ms");
    }

    @Test
    @Timeout(120)
    void defaultErrorHandlerTriesTenTimesThenSkips() throws Exception {
        String topic = createTopic(1);
        String group = "default-handler-" + UUID.randomUUID();
        TopicPartition partition = new TopicPartition(topic, 0);
        send(topic, 2);

        AtomicInteger badAttempts = new AtomicInteger();
        AtomicInteger goodAttempts = new AtomicInteger();
        var properties = new ContainerProperties(topic);
        properties.setGroupId(group);
        properties.setMessageListener((MessageListener<String, String>) record -> {
            if (record.offset() == 0) {
                badAttempts.incrementAndGet();
                throw new IllegalStateException("저장 실패");
            }
            goodAttempts.incrementAndGet();
        });
        Map<String, Object> config = new HashMap<>(baseConsumer());
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        var container = new KafkaMessageListenerContainer<>(new DefaultKafkaConsumerFactory<String, String>(config), properties);
        container.start();
        try {
            await(() -> goodAttempts.get() == 1, "next record is processed after the default handler gives up");
            await(() -> committed(group, partition) != null && committed(group, partition).offset() == 2, "offset moves past");
        } finally {
            container.stop();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("attemptsOnFailedRecord", badAttempts.get());
        result.put("nextRecordProcessed", goodAttempts.get());
        result.put("committedOffset", committed(group, partition).offset());
        System.out.println("DEFAULT_ERROR_HANDLER_EXPERIMENT " + JSON.writeValueAsString(result));

        assertThat(badAttempts).hasValue(10);
        assertThat(goodAttempts).hasValue(1);
    }

    private Map<String, Object> restartOneOfTwoConsumers(String instanceId) throws Exception {
        String topic = createTopic(2);
        String group = "restart-" + UUID.randomUUID();
        AtomicInteger rebalances = new AtomicInteger();
        var listener = new ConsumerRebalanceListener() {
            @Override
            public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
                if (!partitions.isEmpty()) {
                    rebalances.incrementAndGet();
                }
            }

            @Override
            public void onPartitionsAssigned(Collection<TopicPartition> partitions) {}
        };
        Map<String, Object> session = new HashMap<>(Map.of(
            ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 10_000, ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 1_000));
        if (instanceId != null) {
            session.put(ConsumerConfig.GROUP_INSTANCE_ID_CONFIG, instanceId);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("groupInstanceId", instanceId);
        try (var other = consumer(group, false, Map.of(
            ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 10_000, ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 1_000))) {
            other.subscribe(List.of(topic), listener);
            var restarted = consumer(group, false, session);
            restarted.subscribe(List.of(topic));
            await(() -> {
                other.poll(Duration.ofMillis(100));
                restarted.poll(Duration.ofMillis(100));
                return other.assignment().size() == 1 && restarted.assignment().size() == 1;
            }, "both consumers own one partition");
            rebalances.set(0);

            restarted.close();
            Thread.sleep(500);
            other.poll(Duration.ofMillis(100));
            result.put("membersAfterClose", members(group));

            try (var returned = consumer(group, false, session)) {
                returned.subscribe(List.of(topic));
                long deadline = System.nanoTime() + Duration.ofSeconds(4).toNanos();
                while (System.nanoTime() < deadline) {
                    other.poll(Duration.ofMillis(100));
                    returned.poll(Duration.ofMillis(100));
                }
                result.put("rebalancesSeenByOther", rebalances.get());
                result.put("returnedPartitions", returned.assignment().size());
            }
        }
        return result;
    }

    private Map<String, Object> pollThenCloseWithoutProcessing(boolean autoCommit) throws Exception {
        String topic = createTopic(1);
        String group = "auto-" + autoCommit + "-" + UUID.randomUUID();
        TopicPartition partition = new TopicPartition(topic, 0);
        send(topic, 3);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enableAutoCommit", autoCommit);
        try (var consumer = consumer(group, autoCommit, Map.of(ConsumerConfig.AUTO_COMMIT_INTERVAL_MS_CONFIG, 60_000))) {
            consumer.subscribe(List.of(topic));
            List<ConsumerRecord<String, String>> received = new ArrayList<>();
            await(() -> {
                consumer.poll(Duration.ofMillis(200)).forEach(received::add);
                return received.size() == 3;
            }, "three records");
            result.put("positionBeforeClose", consumer.position(partition));
        }
        OffsetAndMetadata committed = committed(group, partition);
        result.put("committedAfterClose", committed == null ? null : committed.offset());
        result.put("readAfterRestart", readAll(topic, group).size());
        return result;
    }

    private List<String> readAll(String topic, String group) throws Exception {
        List<String> values = new ArrayList<>();
        try (var consumer = consumer(group, false, Map.of())) {
            consumer.subscribe(List.of(topic));
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(200)).forEach(record -> values.add(record.value()));
            }
        }
        return values;
    }

    private Process childConsumer(String topic, String group, boolean autoCommit) throws Exception {
        String classpath = String.join(File.pathSeparator, location(KafkaConsumer.class),
            location(KafkaSettingsChildConsumer.class), location(org.slf4j.Logger.class));
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        return new ProcessBuilder(java, "-cp", classpath, KafkaSettingsChildConsumer.class.getName(),
            broker.bootstrapServers(), topic, group, Boolean.toString(autoCommit))
            .redirectError(ProcessBuilder.Redirect.DISCARD).start();
    }

    private String location(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }

    private int members(String group) throws Exception {
        ConsumerGroupDescription description = admin.describeConsumerGroups(List.of(group)).all()
            .get(10, TimeUnit.SECONDS).get(group);
        return description.members().size();
    }

    private OffsetAndMetadata committed(String group, TopicPartition partition) throws Exception {
        return admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata()
            .get(10, TimeUnit.SECONDS).get(partition);
    }

    private String createTopic(int partitions) throws Exception {
        String topic = "settings-" + UUID.randomUUID();
        admin.createTopics(List.of(new NewTopic(topic, partitions, (short) 1))).all().get(15, TimeUnit.SECONDS);
        return topic;
    }

    private void send(String topic, int count) throws Exception {
        try (var producer = new KafkaProducer<String, String>(Map.of(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.bootstrapServers(),
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class))) {
            for (int i = 0; i < count; i++) {
                producer.send(new ProducerRecord<>(topic, "order", "order-" + i));
            }
            producer.flush();
        }
        var partition = new TopicPartition(topic, 0);
        assertThat(admin.listOffsets(Map.of(partition, OffsetSpec.latest())).all().get(10, TimeUnit.SECONDS)
            .get(partition).offset()).isEqualTo(count);
    }

    private KafkaConsumer<String, String> consumer(String group, boolean autoCommit, Map<String, Object> extra) {
        Map<String, Object> config = new HashMap<>(baseConsumer());
        config.put(ConsumerConfig.GROUP_ID_CONFIG, group);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, autoCommit);
        config.putAll(extra);
        return new KafkaConsumer<>(config);
    }

    private Map<String, Object> baseConsumer() {
        return Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.bootstrapServers(),
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    }

    private void await(Callable<Boolean> condition, String what) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(40).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.call()) {
                return;
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("timed out waiting: " + what);
    }
}
