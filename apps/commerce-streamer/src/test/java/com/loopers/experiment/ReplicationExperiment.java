package com.loopers.experiment;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.common.ElectionType;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.TopicPartitionInfo;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.Network;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 단일 broker 로는 확인하지 못하는 복제 장애 보장을 broker 3대 cluster 로 확인함 (6주차 설계 3.5 "acks 와 복제 보장"). 제품 코드가 아님.
 * <ul>
 *   <li>노드마다 broker 겸 controller(KRaft). 2대를 함께 멈추면 controller quorum 이 깨지므로 한 번에 1대만 멈춤</li>
 *   <li>broker 정지는 docker pause. 빨리 ISR 에서 빠지도록 replica.lag.time.max.ms=5s</li>
 *   <li>ISR 부족은 min.insync.replicas=3 으로, acks=1 의 유실은 replication factor 2 topic 에서 만듦</li>
 *   <li>acks=0 은 broker 가 확실히 거절하는 크기 초과 record 로 확인함</li>
 * </ul>
 * 실행: RUN_EXPERIMENT=true ./gradlew :apps:commerce-streamer:test --tests '*ReplicationExperiment'
 */
@EnabledIfEnvironmentVariable(named = "RUN_EXPERIMENT", matches = "true")
class ReplicationExperiment {

    private static final Logger log = LoggerFactory.getLogger(ReplicationExperiment.class);
    private static final Path REPORT = Path.of("build", "experiment", "replication.md");
    private static final Duration WAIT = Duration.ofSeconds(60);
    // controller 가 멈춘 leader 를 fence 하는 시간(broker.session.timeout.ms 기본 9초)보다 넉넉히 봄
    private static final Duration OBSERVE = Duration.ofSeconds(90);

    private static Network network;
    private static final List<ConfluentKafkaContainer> brokers = new ArrayList<>();
    private static final List<String> report = new ArrayList<>();
    // 멈춘 broker 를 접속 주소에 넣으면 클라이언트가 그쪽으로 붙으려다 시간을 넘기므로 뺌
    private static final Set<Integer> paused = new HashSet<>();

    @BeforeAll
    static void startCluster() {
        network = Network.newNetwork();
        String voters = "1@kafka1:9094,2@kafka2:9094,3@kafka3:9094";
        for (int id = 1; id <= 3; id++) {
            String host = "kafka" + id;
            ConfluentKafkaContainer broker = new ConfluentKafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.5.3"))
                .withNetwork(network)
                .withNetworkAliases(host)
                .withCreateContainerCmdModifier(cmd -> cmd.withHostName(host))
                .withEnv("KAFKA_NODE_ID", String.valueOf(id))
                .withEnv("KAFKA_CONTROLLER_QUORUM_VOTERS", voters)
                .withEnv("KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR", "3")
                .withEnv("KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR", "3")
                .withEnv("KAFKA_TRANSACTION_STATE_LOG_MIN_ISR", "2")
                .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false")
                .withEnv("KAFKA_REPLICA_LAG_TIME_MAX_MS", "5000");
            brokers.add(broker);
        }
        // quorum 이 과반이 돼야 뜨므로 동시에 시작함
        Startables.deepStart(brokers).join();
    }

    @AfterAll
    static void stopCluster() throws IOException {
        brokers.forEach(broker -> {
            unpause(broker);
            broker.stop();
        });
        network.close();
        Files.createDirectories(REPORT.getParent());
        Files.writeString(REPORT, "# 복제 장애 실험\n\n" + String.join("\n", report) + "\n");
    }

    private static String bootstrap() {
        List<String> addresses = new ArrayList<>();
        for (int id = 1; id <= brokers.size(); id++) {
            if (!paused.contains(id)) {
                addresses.add(broker(id).getBootstrapServers());
            }
        }
        return String.join(",", addresses);
    }

    private static ConfluentKafkaContainer broker(int nodeId) {
        return brokers.get(nodeId - 1);
    }

    private static void pause(ConfluentKafkaContainer broker) {
        broker.getDockerClient().pauseContainerCmd(broker.getContainerId()).exec();
        paused.add(brokers.indexOf(broker) + 1);
    }

    private static void unpause(ConfluentKafkaContainer broker) {
        Boolean paused = broker.getDockerClient().inspectContainerCmd(broker.getContainerId()).exec().getState().getPaused();
        if (Boolean.TRUE.equals(paused)) {
            broker.getDockerClient().unpauseContainerCmd(broker.getContainerId()).exec();
        }
        ReplicationExperiment.paused.remove(brokers.indexOf(broker) + 1);
    }

    private static AdminClient admin() {
        return AdminClient.create(Map.of(
            AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap(),
            AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000,
            AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 10000
        ));
    }

    private static void createTopic(String topic, int replicas, int minInsync, boolean unclean) throws Exception {
        createTopic(topic, replicas, minInsync, unclean, Map.of());
    }

    private static void createTopic(String topic, int replicas, int minInsync, boolean unclean, Map<String, String> extra) throws Exception {
        try (AdminClient admin = admin()) {
            Map<String, String> configs = new HashMap<>(extra);
            configs.put("min.insync.replicas", String.valueOf(minInsync));
            configs.put("unclean.leader.election.enable", String.valueOf(unclean));
            NewTopic newTopic = new NewTopic(topic, 1, (short) replicas).configs(configs);
            admin.createTopics(List.of(newTopic)).all().get(20, TimeUnit.SECONDS);
        }
        // metadata 가 퍼질 때까지 기다림. 만든 직후 조회하면 아직 모르는 topic 으로 답함
        waitUntil(topic + " 의 leader · ISR 확정", () -> isrSize(topic, replicas));
    }

    /** 리더 · ISR. broker 마다 metadata 갱신 시점이 달라 아직 모르는 topic 이라고 답하면 잠시 뒤 다시 물음 */
    private static TopicPartitionInfo partitionOf(String topic) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(15));
        while (true) {
            try (AdminClient admin = admin()) {
                TopicDescription description = admin.describeTopics(List.of(topic)).allTopicNames().get(15, TimeUnit.SECONDS).get(topic);
                return description.partitions().get(0);
            } catch (ExecutionException e) {
                if (!(e.getCause() instanceof org.apache.kafka.common.errors.UnknownTopicOrPartitionException) || Instant.now().isAfter(deadline)) {
                    throw e;
                }
                Thread.sleep(500);
            }
        }
    }

    private static String describe(TopicPartitionInfo partition) {
        Node leader = partition.leader();
        return "leader=" + (leader == null || leader.isEmpty() ? "없음" : leader.id())
            + " ISR=" + partition.isr().stream().map(Node::id).sorted().toList();
    }

    private static void waitUntil(String what, BooleanSupplier condition) throws InterruptedException {
        Instant deadline = Instant.now().plus(WAIT);
        while (!condition.getAsBoolean()) {
            assertThat(Instant.now()).as(what).isBefore(deadline);
            Thread.sleep(500);
        }
    }

    private static boolean isrSize(String topic, int size) {
        try {
            return partitionOf(topic).isr().size() == size;
        } catch (Exception e) {
            return false;
        }
    }

    private static KafkaProducer<String, String> producer(String acks) {
        return new KafkaProducer<>(Map.of(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap(),
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
            ProducerConfig.ACKS_CONFIG, acks,
            // acks 차이만 보려고 둘 다 idempotence 를 끔. 켜면 producer ID 요청이 멈춘 broker 로 가 시간을 넘기는 일이 섞임
            ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, false,
            ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000,
            ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 20000,
            ProducerConfig.MAX_BLOCK_MS_CONFIG, 10000
        ));
    }

    /** 보낸 결과: 성공이면 "성공 offset=n", 실패면 예외 종류 */
    private static String send(KafkaProducer<String, String> producer, String topic, String value) {
        try {
            return "성공 offset=" + producer.send(new ProducerRecord<>(topic, "k", value)).get(30, TimeUnit.SECONDS).offset();
        } catch (ExecutionException e) {
            return "실패 " + e.getCause().getClass().getSimpleName();
        } catch (Exception e) {
            return "실패 " + e.getClass().getSimpleName();
        }
    }

    /** 새 leader 의 metadata 를 받을 때까지 다시 시도해 처음부터 끝 offset 까지 읽음 */
    private static Set<String> readAll(String topic) throws InterruptedException {
        Instant deadline = Instant.now().plus(WAIT);
        while (true) {
            try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap(),
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
                TopicPartition partition = new TopicPartition(topic, 0);
                consumer.assign(List.of(partition));
                consumer.seekToBeginning(List.of(partition));
                long end = consumer.endOffsets(List.of(partition), Duration.ofSeconds(10)).get(partition);
                Set<String> values = new HashSet<>();
                while (consumer.position(partition, Duration.ofSeconds(10)) < end) {
                    assertThat(Instant.now()).as(topic + " 끝까지 읽기").isBefore(deadline);
                    for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                        values.add(record.value());
                    }
                }
                return values;
            } catch (org.apache.kafka.common.errors.TimeoutException e) {
                assertThat(Instant.now()).as(topic + " 새 leader 에서 읽기").isBefore(deadline);
                Thread.sleep(1000);
            }
        }
    }

    private static void record(String line) {
        log.info("[복제] {}", line);
        report.add(line);
    }

    @DisplayName("broker 1대가 멈춰도 ISR 2 ≥ min.insync 2 면 acks=all 쓰기가 성공한다")
    @Test
    void acksAllSurvivesOneFollowerDown() throws Exception {
        String topic = "repl-one-down";
        createTopic(topic, 3, 2, false);
        TopicPartitionInfo before = partitionOf(topic);
        int follower = before.replicas().stream().map(Node::id).filter(id -> id != before.leader().id()).findFirst().orElseThrow();
        pause(broker(follower));
        try {
            waitUntil("follower 가 ISR 에서 빠짐", () -> isrSize(topic, 2));
            String result;
            try (KafkaProducer<String, String> producer = producer("all")) {
                result = send(producer, topic, "v1");
            }
            record("| follower 1대 정지 (RF 3, min.insync 2) | " + describe(before) + " → " + describe(partitionOf(topic)) + " | acks=all: " + result + " |");
            assertThat(result).startsWith("성공");
        } finally {
            unpause(broker(follower));
        }
        waitUntil("ISR 회복", () -> isrSize(topic, 3));
    }

    @DisplayName("ISR 이 min.insync 보다 작으면 acks=all 은 거절되고 acks=1 은 받아들여진다")
    @Test
    void acksAllRejectedWhenIsrBelowMin() throws Exception {
        String topic = "repl-below-min";
        createTopic(topic, 3, 3, false);
        TopicPartitionInfo before = partitionOf(topic);
        int follower = before.replicas().stream().map(Node::id).filter(id -> id != before.leader().id()).findFirst().orElseThrow();
        pause(broker(follower));
        try {
            waitUntil("follower 가 ISR 에서 빠짐", () -> isrSize(topic, 2));
            String all;
            String one;
            try (KafkaProducer<String, String> producer = producer("all")) {
                all = send(producer, topic, "all");
            }
            try (KafkaProducer<String, String> producer = producer("1")) {
                one = send(producer, topic, "one");
            }
            record("| ISR 2 < min.insync 3 (RF 3) | " + describe(partitionOf(topic)) + " | acks=all: " + all + " · acks=1: " + one + " |");
            assertThat(all).startsWith("실패");
            assertThat(one).startsWith("성공");
        } finally {
            unpause(broker(follower));
        }
        waitUntil("ISR 회복", () -> isrSize(topic, 3));
    }

    @DisplayName("leader 가 멈추면 ISR 의 다른 replica 가 leader 가 되고 acks=all 로 확인받은 record 는 남는다")
    @Test
    void acksAllKeepsRecordsAfterLeaderFailover() throws Exception {
        String topic = "repl-leader-down";
        createTopic(topic, 3, 2, false);
        Set<String> acked = new HashSet<>();
        try (KafkaProducer<String, String> producer = producer("all")) {
            for (int i = 0; i < 100; i++) {
                if (send(producer, topic, "v" + i).startsWith("성공")) {
                    acked.add("v" + i);
                }
            }
        }
        TopicPartitionInfo before = partitionOf(topic);
        int leader = before.leader().id();
        pause(broker(leader));
        try {
            waitUntil("새 leader 선출", () -> {
                try {
                    Node now = partitionOf(topic).leader();
                    return now != null && !now.isEmpty() && now.id() != leader;
                } catch (Exception e) {
                    return false;
                }
            });
            TopicPartitionInfo after = partitionOf(topic);
            Set<String> read = readAll(topic);
            record("| leader 정지 (RF 3, min.insync 2, acks=all 로 100건) | " + describe(before) + " → " + describe(after)
                + " | 확인받은 " + acked.size() + "건 중 새 leader 에서 읽힌 " + read.stream().filter(acked::contains).count() + "건 |");
            assertThat(read).containsAll(acked);
        } finally {
            unpause(broker(leader));
        }
    }

    @DisplayName("acks=1 은 follower 가 뒤처진 동안 leader 만 쓴 record 를, leader 장애 뒤 다른 replica 가 leader 가 되면 잃을 수 있다")
    @Test
    void acksOneLosesRecordsOnUncleanFailover() throws Exception {
        recordLossOnUncleanFailover("repl-acks1-loss", "1", 1);
    }

    @DisplayName("acks=all 이어도 min.insync=1 이면 ISR 이 leader 하나일 때 받아들인 record 를 leader 장애 뒤 잃을 수 있다")
    @Test
    void acksAllWithMinInsyncOneLosesRecordsOnUncleanFailover() throws Exception {
        recordLossOnUncleanFailover("repl-all-min1-loss", "all", 1);
    }

    @DisplayName("acks=0 은 broker 가 크기 초과로 거절한 record 도 성공으로 알고, acks=1 은 실패를 안다")
    @Test
    void acksZeroCannotSeeBrokerRejection() throws Exception {
        String topic = "repl-acks0-too-large";
        createTopic(topic, 3, 2, false, Map.of("max.message.bytes", "1024"));
        // 2KB 는 producer 의 max.request.size(1MB) 안이라 클라이언트는 그대로 보내고 broker 만 거절함
        String large = "x".repeat(2048);
        String one;
        String zero;
        try (KafkaProducer<String, String> producer = producer("1")) {
            one = send(producer, topic, "one-" + large);
        }
        try (KafkaProducer<String, String> producer = producer("0")) {
            zero = send(producer, topic, "zero-" + large);
            producer.flush();
        }
        Set<String> read = readAll(topic);
        record("| 크기 초과 record (topic max.message.bytes 1KB, 2KB 전송) | " + describe(partitionOf(topic))
            + " | acks=1: " + one + " · acks=0: " + zero + " · topic 에 저장된 record " + read.size() + "건 |");
        assertThat(one).startsWith("실패");
        assertThat(zero).startsWith("성공");
        assertThat(read).isEmpty();
    }

    @DisplayName("unclean 선출을 허용하지 않으면, ISR 밖 replica 만 남았을 때 leader 가 서지 않는다")
    @Test
    void noLeaderWithoutUncleanElection() throws Exception {
        observeLeaderWithoutElectionRequest(false);
    }

    @DisplayName("unclean 선출을 허용하면, 선출 요청 없이도 ISR 밖 replica 가 leader 가 되는지 본다")
    @Test
    void observesUncleanElectionWithoutRequest() throws Exception {
        observeLeaderWithoutElectionRequest(true);
    }

    private void observeLeaderWithoutElectionRequest(boolean unclean) throws Exception {
        String topic = "repl-no-request-unclean-" + unclean;
        LeaderOnlyWrites writes = writeWhileFollowerBehind(topic, "1", 1, unclean);
        failLeaderAndReviveFollower(writes);
        Instant start = Instant.now();
        Instant deadline = start.plus(OBSERVE);
        String outcome = "관찰 " + OBSERVE.toSeconds() + "초 동안 leader 없음";
        String state = describe(partitionOf(topic));
        Set<String> read = Set.of();
        try {
            while (Instant.now().isBefore(deadline)) {
                TopicPartitionInfo now = partitionOf(topic);
                state = describe(now);
                if (now.leader() != null && !now.leader().isEmpty() && now.leader().id() == writes.follower()) {
                    outcome = Duration.between(start, Instant.now()).toSeconds() + "초 뒤 뒤처진 follower 가 leader 가 됨";
                    read = readAll(topic);
                    break;
                }
                Thread.sleep(1000);
            }
        } finally {
            unpause(broker(writes.leader()));
        }
        // 되살린 broker 가 다시 붙기 전에 다음 topic 을 만들면 controller 가 UnknownServerException 으로 답함
        waitUntil(topic + " 의 leader 복귀", () -> {
            try {
                Node now = partitionOf(topic).leader();
                return now != null && !now.isEmpty();
            } catch (Exception e) {
                return false;
            }
        });
        Set<String> survived = read;
        String loss = survived.isEmpty() ? "" : " · 확인받은 " + writes.acked().size() + "건 중 사라진 "
            + writes.acked().stream().filter(value -> !survived.contains(value)).count() + "건";
        record("| 선출 요청 없음, unclean " + (unclean ? "허용" : "불허") + " (RF 2, acks=1 로 leader 만 20건 → leader 정지) | "
            + writes.isrBeforeFailover() + " → " + state + " | " + outcome + loss + " |");
        if (!unclean) {
            assertThat(outcome).startsWith("관찰");
        }
    }

    @DisplayName("unclean 선출 뒤 옛 leader 가 돌아오면 새 leader 기준으로 자기 로그를 잘라, leader 로 되돌려도 잃은 record 는 돌아오지 않는다")
    @Test
    void oldLeaderTruncatesAfterUncleanElection() throws Exception {
        LeaderOnlyWrites writes = writeWhileFollowerBehind("repl-truncate-on-return", "1", 1, true);
        String topic = writes.topic();
        assertThat(partitionOf(topic).replicas().get(0).id()).as("처음 leader 가 preferred replica").isEqualTo(writes.leader());
        long endBefore = endOffset(topic);
        failLeaderAndReviveFollower(writes);
        try {
            electUntilLeader(topic, ElectionType.UNCLEAN, writes.follower());
        } finally {
            unpause(broker(writes.leader()));
        }
        long endOnNewLeader = endOffset(topic);
        waitUntil("옛 leader 가 ISR 로 복귀", () -> isrSize(topic, 2));
        String rejoined = describe(partitionOf(topic));
        electUntilLeader(topic, ElectionType.PREFERRED, writes.leader());
        long endBack = endOffset(topic);
        Set<String> read = readAll(topic);
        long lost = writes.acked().stream().filter(value -> !read.contains(value)).count();
        record("| unclean 선출 뒤 옛 leader 복귀 → 다시 leader (RF 2, acks=1 로 leader 만 20건) | 장애 전 " + writes.isrBeforeFailover()
            + " · 복귀 뒤 " + rejoined + " → " + describe(partitionOf(topic)) + " | 옛 leader 의 끝 offset " + endBefore
            + " → 새 leader " + endOnNewLeader + " → 옛 leader 로 되돌린 뒤 " + endBack + " · 확인받은 " + writes.acked().size() + "건 중 사라진 " + lost + "건 |");
        assertThat(endBack).isEqualTo(endOnNewLeader);
        assertThat(lost).isEqualTo(20);
    }

    /** leader 가 target 이 될 때까지 선출을 요청함. 이미 선출됐는데 다시 요청하면 ElectionNotNeeded 가 오므로 무시함 */
    private static void electUntilLeader(String topic, ElectionType type, int target) throws Exception {
        Instant deadline = Instant.now().plus(WAIT);
        while (true) {
            Node now = partitionOf(topic).leader();
            if (now != null && !now.isEmpty() && now.id() == target) {
                return;
            }
            assertThat(Instant.now()).as(topic + " 의 leader 를 " + target + " 로 선출").isBefore(deadline);
            try (AdminClient admin = admin()) {
                admin.electLeaders(type, Set.of(new TopicPartition(topic, 0))).all().get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.info("[복제] {} 선출 요청 결과: {}", type, (e.getCause() == null ? e : e.getCause()).getClass().getSimpleName());
            }
            Thread.sleep(1000);
        }
    }

    /** 현재 leader 의 끝 offset. leader 가 막 바뀌어 metadata 가 늦으면 다시 물음 */
    private static long endOffset(String topic) throws InterruptedException {
        Instant deadline = Instant.now().plus(WAIT);
        while (true) {
            try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap(),
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
                TopicPartition partition = new TopicPartition(topic, 0);
                return consumer.endOffsets(List.of(partition), Duration.ofSeconds(10)).get(partition);
            } catch (org.apache.kafka.common.errors.TimeoutException e) {
                assertThat(Instant.now()).as(topic + " 끝 offset 읽기").isBefore(deadline);
                Thread.sleep(1000);
            }
        }
    }

    private record LeaderOnlyWrites(String topic, int leader, int follower, Set<String> acked, String isrBeforeFailover) {
    }

    /**
     * RF 2: leader 와 follower 하나. 세 번째 broker 는 controller quorum 만 지킴.
     * follower 를 멈춰 ISR 을 leader 하나로 줄인 뒤 20건을 보냄
     */
    private static LeaderOnlyWrites writeWhileFollowerBehind(String topic, String acks, int minInsync, boolean unclean) throws Exception {
        createTopic(topic, 2, minInsync, unclean);
        TopicPartitionInfo before = partitionOf(topic);
        int leader = before.leader().id();
        int follower = before.replicas().stream().map(Node::id).filter(id -> id != leader).findFirst().orElseThrow();
        Set<String> acked = new HashSet<>();
        try (KafkaProducer<String, String> producer = producer(acks)) {
            if (send(producer, topic, "replicated").startsWith("성공")) {
                acked.add("replicated");
            }
            Thread.sleep(2000);
            pause(broker(follower));
            waitUntil("follower 가 ISR 에서 빠짐", () -> isrSize(topic, 1));
            for (int i = 0; i < 20; i++) {
                if (send(producer, topic, "leader-only-" + i).startsWith("성공")) {
                    acked.add("leader-only-" + i);
                }
            }
        }
        return new LeaderOnlyWrites(topic, leader, follower, acked, describe(partitionOf(topic)));
    }

    /** leader 를 멈추고 뒤처진 follower 를 살림. 살아 있는 controller 는 follower + 세 번째 노드로 과반 유지 */
    private static void failLeaderAndReviveFollower(LeaderOnlyWrites writes) {
        pause(broker(writes.leader()));
        unpause(broker(writes.follower()));
    }

    /** 뒤처진 follower 가 leader 가 되도록 unclean 선출을 요청하고, 확인받은 record 중 사라진 수를 기록함 */
    private void recordLossOnUncleanFailover(String topic, String acks, int minInsync) throws Exception {
        LeaderOnlyWrites writes = writeWhileFollowerBehind(topic, acks, minInsync, true);
        int leader = writes.leader();
        int follower = writes.follower();
        Set<String> acked = writes.acked();
        String isrBeforeFailover = writes.isrBeforeFailover();
        failLeaderAndReviveFollower(writes);
        String after;
        Set<String> read;
        String election = "자동 선출됨";
        try {
            Instant deadline = Instant.now().plus(WAIT);
            Node now = null;
            while (Instant.now().isBefore(deadline)) {
                now = partitionOf(topic).leader();
                if (now != null && !now.isEmpty() && now.id() == follower) {
                    break;
                }
                // ISR 밖 replica 는 저절로 leader 가 되지 않아 unclean 선출을 직접 요청함
                try (AdminClient admin = admin()) {
                    admin.electLeaders(ElectionType.UNCLEAN, Set.of(new TopicPartition(topic, 0))).all().get(10, TimeUnit.SECONDS);
                    election = "unclean 선출 요청";
                } catch (Exception e) {
                    election = "unclean 선출 요청 실패: " + (e.getCause() == null ? e : e.getCause()).getClass().getSimpleName();
                }
                Thread.sleep(1000);
            }
            after = describe(partitionOf(topic));
            read = now != null && !now.isEmpty() && now.id() == follower ? readAll(topic) : Set.of();
        } finally {
            unpause(broker(leader));
        }
        long lost = acked.stream().filter(value -> !read.contains(value)).count();
        record("| acks=" + acks + ", min.insync " + minInsync + ", follower 뒤처진 동안 leader 만 20건 → leader 정지 (RF 2, unclean 허용) | "
            + isrBeforeFailover + " → " + after + " (" + election + ") | "
            + (read.isEmpty() && !after.contains("leader=" + follower)
                ? "새 leader 없음 → partition 을 읽을 수 없음(유실 판단 불가)"
                : "확인받은 " + acked.size() + "건 중 새 leader 에서 사라진 " + lost + "건") + " |");
    }
}
