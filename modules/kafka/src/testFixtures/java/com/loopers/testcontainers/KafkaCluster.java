package com.loopers.testcontainers;

import org.testcontainers.containers.FixedHostPortGenericContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.lifecycle.Startables;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Testcontainers로 띄우는 Kafka broker 3대 (W6 plan.md D-5). 설정은 docker/infra-compose.yml과 같다 —
 * 모두 broker·controller를 겸한 KRaft quorum 3, 기본 RF 3, min.insync.replicas 2, unclean leader election 끔.
 * <p>
 * 광고 주소(advertised listener)는 컨테이너가 뜨기 전에 정해야 하므로, 빈 호스트 포트를 먼저 골라 고정 바인딩한다.
 * 컨테이너는 Testcontainers(Ryuk)가 JVM 종료 때 지운다. broker를 멈추는 실험은 공유 클러스터를 망가뜨리므로 {@link #start()}로 따로 띄운다.
 */
public final class KafkaCluster {

    private static final String IMAGE = "apache/kafka:3.8.1"; // kafka-clients 3.8.1과 맞춘다
    private static final String CLUSTER_ID = "bG9vcGVyc3c2AGthZrYaAA";
    private static final int SIZE = 3;

    private final Network network;
    private final List<GenericContainer<?>> brokers;
    private final List<Integer> hostPorts;

    private KafkaCluster(Network network, List<GenericContainer<?>> brokers, List<Integer> hostPorts) {
        this.network = network;
        this.brokers = brokers;
        this.hostPorts = hostPorts;
    }

    /**
     * 새 클러스터를 띄운다. quorum은 세 노드가 모두 떠야 이뤄지므로 동시에 시작한다.
     */
    @SuppressWarnings("deprecation") // FixedHostPortGenericContainer: 광고 주소에 쓸 호스트 포트를 미리 고정해야 한다
    public static KafkaCluster start() {
        Network network = Network.newNetwork();
        List<Integer> hostPorts = IntStream.range(0, SIZE).mapToObj(i -> freePort()).toList();
        String voters = IntStream.rangeClosed(1, SIZE)
            .mapToObj(id -> id + "@kafka-" + id + ":9093")
            .collect(Collectors.joining(","));

        List<GenericContainer<?>> brokers = new ArrayList<>();
        for (int id = 1; id <= SIZE; id++) {
            int hostPort = hostPorts.get(id - 1);
            GenericContainer<?> broker = new FixedHostPortGenericContainer<>(IMAGE)
                .withFixedExposedPort(hostPort, 19092)
                .withNetwork(network)
                .withNetworkAliases("kafka-" + id)
                .withEnv("CLUSTER_ID", CLUSTER_ID)
                .withEnv("KAFKA_NODE_ID", String.valueOf(id))
                .withEnv("KAFKA_PROCESS_ROLES", "broker,controller")
                .withEnv("KAFKA_CONTROLLER_QUORUM_VOTERS", voters)
                .withEnv("KAFKA_LISTENERS", "PLAINTEXT://:9092,CONTROLLER://:9093,PLAINTEXT_HOST://:19092")
                .withEnv("KAFKA_ADVERTISED_LISTENERS", "PLAINTEXT://kafka-" + id + ":9092,PLAINTEXT_HOST://localhost:" + hostPort)
                .withEnv("KAFKA_LISTENER_SECURITY_PROTOCOL_MAP", "PLAINTEXT:PLAINTEXT,CONTROLLER:PLAINTEXT,PLAINTEXT_HOST:PLAINTEXT")
                .withEnv("KAFKA_INTER_BROKER_LISTENER_NAME", "PLAINTEXT")
                .withEnv("KAFKA_CONTROLLER_LISTENER_NAMES", "CONTROLLER")
                .withEnv("KAFKA_LOG_DIRS", "/var/lib/kafka/data")
                .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false")
                .withEnv("KAFKA_DEFAULT_REPLICATION_FACTOR", "3")
                .withEnv("KAFKA_MIN_INSYNC_REPLICAS", "2")
                .withEnv("KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR", "3")
                .withEnv("KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR", "3")
                .withEnv("KAFKA_TRANSACTION_STATE_LOG_MIN_ISR", "2")
                .withEnv("KAFKA_UNCLEAN_LEADER_ELECTION_ENABLE", "false")
                .withEnv("KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS", "0")
                .waitingFor(Wait.forLogMessage(".*Kafka Server started.*", 1).withStartupTimeout(Duration.ofMinutes(2)));
            brokers.add(broker);
        }
        Startables.deepStart(brokers).join();
        return new KafkaCluster(network, brokers, hostPorts);
    }

    /**
     * 호스트에서 붙는 bootstrap 주소 3개.
     */
    public String bootstrapServers() {
        return hostPorts.stream().map(port -> "localhost:" + port).collect(Collectors.joining(","));
    }

    /**
     * broker 하나를 정상 종료한다(SIGTERM → controlled shutdown). nodeId는 1부터.
     */
    public void stopBroker(int nodeId) {
        GenericContainer<?> broker = brokers.get(nodeId - 1);
        broker.getDockerClient().stopContainerCmd(broker.getContainerId()).withTimeout(30).exec();
    }

    public void close() {
        brokers.forEach(GenericContainer::stop);
        network.close();
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
