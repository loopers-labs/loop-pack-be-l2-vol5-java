package com.loopers.purchase.infrastructure.kafka;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Kafka partition·consumer group 실험")
class KafkaPartitionAssignmentExperimentTest {

    private static final String BOOTSTRAP_SERVERS = System.getenv()
        .getOrDefault("KAFKA_BOOTSTRAP_SERVERS", "localhost:19092");
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration FAILURE_DETECTION_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration STABLE_ASSIGNMENT_WINDOW = Duration.ofMillis(500);

    @Test
    @DisplayName("같은 group consumer 수에 따라 partition이 재배정되고 key로 partition을 고른다.")
    void observesRebalancePartitionLimitAndKeyMapping() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String topic = "w6-pcg-" + suffix;
        String groupId = "w6-pcg-group-" + suffix;
        Set<String> receivedValues = ConcurrentHashMap.newKeySet();
        BlockingQueue<ConsumedRecord> consumedRecords = new LinkedBlockingQueue<>();
        List<ConsumerWorker> workers = new ArrayList<>();

        try (AdminClient admin = AdminClient.create(adminProperties());
             KafkaProducer<String, String> producer = new KafkaProducer<>(producerProperties())) {
            admin.createTopics(List.of(new NewTopic(topic, 2, (short) 1)))
                .all().get(WAIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);

            int brokerCount = admin.describeCluster().nodes()
                .get(WAIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS).size();
            var topicDescription = admin.describeTopics(List.of(topic)).allTopicNames()
                .get(WAIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS).get(topic);
            Map<Integer, String> replicaSummary = topicDescription.partitions().stream()
                .collect(Collectors.toMap(
                    partition -> partition.partition(),
                    partition -> "replicas=" + partition.replicas().size() + ",isr=" + partition.isr().size()
                ));

            startConsumer(topic, groupId, 0, workers, consumedRecords, receivedValues);
            Set<Integer> twoPartitions = Set.of(0, 1);
            List<Set<Integer>> oneConsumer = awaitStableAssignment(
                admin, groupId, workers, 1, topic, twoPartitions, WAIT_TIMEOUT);
            assertThat(oneConsumer).containsExactly(Set.of(0, 1));

            startConsumer(topic, groupId, 1, workers, consumedRecords, receivedValues);
            List<Set<Integer>> twoConsumers = awaitStableAssignment(
                admin, groupId, workers, 2, topic, twoPartitions, WAIT_TIMEOUT);
            assertThat(twoConsumers).allSatisfy(assignment -> assertThat(assignment).hasSize(1));
            assertCompletePartitionAssignment(twoConsumers, twoPartitions);

            startConsumer(topic, groupId, 2, workers, consumedRecords, receivedValues);
            List<Set<Integer>> threeConsumers = awaitStableAssignment(
                admin, groupId, workers, 3, topic, twoPartitions, WAIT_TIMEOUT);
            assertThat(threeConsumers.stream().filter(Set::isEmpty).count()).isEqualTo(1);
            threeConsumers.stream()
                .filter(assignment -> !assignment.isEmpty())
                .forEach(assignment -> assertThat(assignment).hasSize(1));
            assertCompletePartitionAssignment(threeConsumers, twoPartitions);

            long gracefulStopStartedAt = System.nanoTime();
            ConsumerWorker gracefullyStopped = workers.get(0);
            gracefullyStopped.requestStop();
            gracefullyStopped.join(WAIT_TIMEOUT.toMillis());
            assertThat(gracefullyStopped.isAlive()).isFalse();
            List<Set<Integer>> afterGracefulStop = awaitStableAssignment(
                admin, groupId, workers, 2, topic, twoPartitions, WAIT_TIMEOUT);
            assertCompletePartitionAssignment(afterGracefulStop, twoPartitions);
            long gracefulRebalanceMillis = elapsedMillis(gracefulStopStartedAt);

            admin.createPartitions(Map.of(topic, org.apache.kafka.clients.admin.NewPartitions.increaseTo(3)))
                .all().get(WAIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            Set<Integer> threePartitions = Set.of(0, 1, 2);
            List<Set<Integer>> afterPartitionExpansion = awaitStableAssignment(
                admin, groupId, workers, 2, topic, threePartitions, WAIT_TIMEOUT);
            assertCompletePartitionAssignment(afterPartitionExpansion, threePartitions);

            long pollStallStartedAt = System.nanoTime();
            ConsumerWorker stalledConsumer = workers.get(1);
            stalledConsumer.pausePolling();
            List<Set<Integer>> afterPollStall = awaitStableAssignment(
                admin, groupId, workers, 1, topic, threePartitions, FAILURE_DETECTION_TIMEOUT);
            assertThat(afterPollStall).containsExactly(threePartitions);
            long pollStallRebalanceMillis = elapsedMillis(pollStallStartedAt);

            RecordMetadata partitionZeroRecord = send(
                producer, topic, 0, "partition-zero", "partition-zero-value");
            RecordMetadata partitionOneRecord = send(
                producer, topic, 1, "partition-one", "partition-one-value");
            RecordMetadata sameKeyFirst = send(producer, topic, null, "same-key", "same-key-first");
            RecordMetadata sameKeySecond = send(producer, topic, null, "same-key", "same-key-second");

            assertThat(partitionZeroRecord.partition()).isZero();
            assertThat(partitionOneRecord.partition()).isEqualTo(1);
            assertThat(sameKeyFirst.partition()).isEqualTo(sameKeySecond.partition());
            awaitConsumedValues(receivedValues);

            List<ConsumedRecord> observedRecords = drain(consumedRecords);
            System.out.printf(
                "Kafka experiment topic=%s group=%s brokers=%d replicas/isr=%s%n"
                    + "assignment 1=%s 2=%s 3=%s%n"
                    + "explicit sends: p0@%d:%d p1@%d:%d; same-key sends: p%d:%d p%d:%d%n"
                    + "after graceful stop=%s (%dms); after partition expansion=%s%n"
                    + "after poll stall=%s (%dms)%n"
                    + "consumed=%s%n",
                topic,
                groupId,
                brokerCount,
                replicaSummary,
                oneConsumer,
                twoConsumers,
                threeConsumers,
                partitionZeroRecord.partition(),
                partitionZeroRecord.offset(),
                partitionOneRecord.partition(),
                partitionOneRecord.offset(),
                sameKeyFirst.partition(),
                sameKeyFirst.offset(),
                sameKeySecond.partition(),
                sameKeySecond.offset(),
                afterGracefulStop,
                gracefulRebalanceMillis,
                afterPartitionExpansion,
                afterPollStall,
                pollStallRebalanceMillis,
                observedRecords
            );
        } finally {
            stopConsumers(workers);
            deleteTopic(topic);
        }
    }

    private void startConsumer(
        String topic,
        String groupId,
        int index,
        List<ConsumerWorker> workers,
        BlockingQueue<ConsumedRecord> consumedRecords,
        Set<String> receivedValues
    ) throws InterruptedException {
        ConsumerWorker worker = new ConsumerWorker(
            topic,
            groupId,
            "w6-pcg-member-" + index,
            consumedRecords,
            receivedValues
        );
        workers.add(worker);
        worker.start();
        assertThat(worker.awaitStarted()).as("consumer %d starts", index).isTrue();
    }

    private List<Set<Integer>> awaitStableAssignment(
        AdminClient admin,
        String groupId,
        List<ConsumerWorker> workers,
        int expectedConsumerCount,
        String topic,
        Set<Integer> expectedPartitions,
        Duration timeout
    ) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        long stableSince = 0;
        String previousAssignment = "";

        while (System.nanoTime() < deadline) {
            assertNoConsumerFailure(workers);
            ConsumerGroupDescription description = describeGroup(admin, groupId);
            List<Set<Integer>> assignments = assignmentsByMember(description, topic);
            boolean complete = description.members().size() == expectedConsumerCount
                && description.state().name().equals("STABLE")
                && assignments.stream().flatMap(Set::stream).collect(Collectors.toSet()).equals(expectedPartitions)
                && assignments.stream().mapToInt(Set::size).sum() == expectedPartitions.size();
            String currentAssignment = assignments.toString();

            if (complete && currentAssignment.equals(previousAssignment)) {
                if (stableSince == 0) {
                    stableSince = System.nanoTime();
                } else if (System.nanoTime() - stableSince >= STABLE_ASSIGNMENT_WINDOW.toNanos()) {
                    return assignments;
                }
            } else {
                stableSince = 0;
            }
            previousAssignment = currentAssignment;
            TimeUnit.MILLISECONDS.sleep(50);
        }

        throw new AssertionError("Assignment did not stabilize within " + timeout);
    }

    private ConsumerGroupDescription describeGroup(AdminClient admin, String groupId) {
        try {
            return admin.describeConsumerGroups(List.of(groupId))
                .describedGroups()
                .get(groupId)
                .get(1, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new AssertionError("Unable to describe consumer group " + groupId, exception);
        }
    }

    private List<Set<Integer>> assignmentsByMember(ConsumerGroupDescription description, String topic) {
        Map<String, Set<Integer>> assignments = new TreeMap<>();
        description.members().forEach(member -> assignments.put(
            member.consumerId(),
            member.assignment().topicPartitions().stream()
                .filter(topicPartition -> topicPartition.topic().equals(topic))
                .map(TopicPartition::partition)
                .collect(Collectors.toSet())
        ));
        return List.copyOf(assignments.values());
    }

    private void assertNoConsumerFailure(List<ConsumerWorker> workers) {
        workers.stream()
            .map(ConsumerWorker::failure)
            .filter(failure -> failure != null)
            .findFirst()
            .ifPresent(failure -> {
                throw new AssertionError("Kafka consumer worker failed", failure);
            });
    }

    private void assertCompletePartitionAssignment(List<Set<Integer>> assignments, Set<Integer> expectedPartitions) {
        List<Integer> assignedPartitions = assignments.stream()
            .flatMap(Set::stream)
            .sorted(Comparator.naturalOrder())
            .toList();
        assertThat(assignedPartitions).containsExactlyElementsOf(expectedPartitions.stream().sorted().toList());
    }

    private long elapsedMillis(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    private RecordMetadata send(
        KafkaProducer<String, String> producer,
        String topic,
        Integer partition,
        String key,
        String value
    ) throws Exception {
        ProducerRecord<String, String> record = partition == null
            ? new ProducerRecord<>(topic, key, value)
            : new ProducerRecord<>(topic, partition, key, value);
        return producer.send(record).get(WAIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
    }

    private void awaitConsumedValues(Set<String> receivedValues) throws InterruptedException {
        Set<String> expectedValues = Set.of(
            "partition-zero-value",
            "partition-one-value",
            "same-key-first",
            "same-key-second"
        );
        long deadline = System.nanoTime() + WAIT_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline && !receivedValues.containsAll(expectedValues)) {
            TimeUnit.MILLISECONDS.sleep(50);
        }
        assertThat(receivedValues).containsAll(expectedValues);
    }

    private List<ConsumedRecord> drain(BlockingQueue<ConsumedRecord> consumedRecords) {
        List<ConsumedRecord> result = new ArrayList<>();
        consumedRecords.drainTo(result);
        return result;
    }

    private void stopConsumers(List<ConsumerWorker> workers) throws InterruptedException {
        workers.forEach(ConsumerWorker::requestStop);
        for (ConsumerWorker worker : workers) {
            worker.join(WAIT_TIMEOUT.toMillis());
            assertThat(worker.isAlive()).as("consumer worker stops").isFalse();
        }
    }

    private void deleteTopic(String topic) throws Exception {
        try (AdminClient admin = AdminClient.create(adminProperties())) {
            admin.deleteTopics(List.of(topic)).all().get(WAIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        }
    }

    private Properties adminProperties() {
        Properties properties = new Properties();
        properties.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
        return properties;
    }

    private Properties producerProperties() {
        Properties properties = new Properties();
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        properties.put(ProducerConfig.ACKS_CONFIG, "all");
        properties.put(ProducerConfig.CLIENT_ID_CONFIG, "w6-pcg-producer");
        return properties;
    }

    private static final class ConsumerWorker extends Thread {

        private final String topic;
        private final String groupId;
        private final String clientId;
        private final BlockingQueue<ConsumedRecord> consumedRecords;
        private final Set<String> receivedValues;
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final AtomicBoolean pollingPaused = new AtomicBoolean();
        private final AtomicReference<KafkaConsumer<String, String>> consumer = new AtomicReference<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);

        private ConsumerWorker(
            String topic,
            String groupId,
            String clientId,
            BlockingQueue<ConsumedRecord> consumedRecords,
            Set<String> receivedValues
        ) {
            super(clientId);
            this.topic = topic;
            this.groupId = groupId;
            this.clientId = clientId;
            this.consumedRecords = consumedRecords;
            this.receivedValues = receivedValues;
        }

        @Override
        public void run() {
            try (KafkaConsumer<String, String> kafkaConsumer =
                     new KafkaConsumer<>(consumerProperties(groupId, clientId))) {
                consumer.set(kafkaConsumer);
                kafkaConsumer.subscribe(List.of(topic));
                started.countDown();
                while (running.get()) {
                    if (pollingPaused.get()) {
                        TimeUnit.MILLISECONDS.sleep(50);
                        continue;
                    }
                    var records = kafkaConsumer.poll(Duration.ofMillis(100));
                    for (ConsumerRecord<String, String> record : records) {
                        consumedRecords.add(new ConsumedRecord(
                            clientId,
                            record.partition(),
                            record.offset(),
                            record.key(),
                            record.value()
                        ));
                        receivedValues.add(record.value());
                    }
                }
            } catch (WakeupException exception) {
                if (running.get()) {
                    failure.set(exception);
                }
            } catch (Throwable throwable) {
                failure.set(throwable);
            } finally {
                started.countDown();
            }
        }

        private static Properties consumerProperties(String groupId, String clientId) {
            Properties properties = new Properties();
            properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
            properties.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
            properties.put(ConsumerConfig.CLIENT_ID_CONFIG, clientId);
            properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
            properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
            properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
            properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
            properties.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 3_000);
            properties.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 6_000);
            properties.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 2_000);
            properties.put(ConsumerConfig.METADATA_MAX_AGE_CONFIG, 1_000);
            return properties;
        }

        private boolean awaitStarted() throws InterruptedException {
            return started.await(WAIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        }

        private Throwable failure() {
            return failure.get();
        }

        private void pausePolling() {
            pollingPaused.set(true);
        }

        private void requestStop() {
            running.set(false);
            KafkaConsumer<String, String> kafkaConsumer = consumer.get();
            if (kafkaConsumer != null) {
                kafkaConsumer.wakeup();
            }
        }
    }

    private record ConsumedRecord(String clientId, int partition, long offset, String key, String value) {
    }
}
