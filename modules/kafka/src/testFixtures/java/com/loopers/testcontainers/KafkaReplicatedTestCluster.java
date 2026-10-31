package com.loopers.testcontainers;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import org.apache.kafka.common.Uuid;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/** 데이터 broker 3개와 별도 controller 1개. 데이터 장애와 관리 quorum 장애를 분리한 실험 조건이다. */
public final class KafkaReplicatedTestCluster implements AutoCloseable {
    private final Network network = Network.newNetwork();
    private final List<GenericContainer<?>> brokers = new ArrayList<>();
    private final GenericContainer<?> controller;

    public KafkaReplicatedTestCluster() {
        String clusterId = Uuid.randomUuid().toString();
        controller = base(clusterId, 100, "controller")
            .withNetworkAliases("controller")
            .withEnv("KAFKA_CFG_LISTENERS", "CONTROLLER://:9093");
        Set<Integer> usedPorts = new HashSet<>();
        String host = DockerClientFactory.instance().dockerHostIpAddress();
        for (int id = 1; id <= 3; id++) {
            int externalPort;
            do {
                externalPort = freePort();
            } while (!usedPorts.add(externalPort));
            int port = externalPort;
            brokers.add(base(clusterId, id, "broker")
                .withNetworkAliases("broker-" + id)
                .withExposedPorts(19092)
                .withCreateContainerCmdModifier(command -> command.getHostConfig().withPortBindings(
                    new PortBinding(Ports.Binding.bindPort(port), new ExposedPort(19092))))
                .withEnv("KAFKA_CFG_LISTENERS", "INTERNAL://:9092,EXTERNAL://:19092")
                .withEnv("KAFKA_CFG_ADVERTISED_LISTENERS",
                    "INTERNAL://broker-" + id + ":9092,EXTERNAL://" + host + ":" + port));
        }
        try {
            controller.start();
            CompletableFuture.allOf(brokers.stream().map(broker -> CompletableFuture.runAsync(broker::start))
                .toArray(CompletableFuture[]::new)).join();
        } catch (RuntimeException failure) {
            try {
                close();
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    private GenericContainer<?> base(String clusterId, int id, String role) {
        return new GenericContainer<>(DockerImageName.parse("bitnamilegacy/kafka:3.5.1"))
            .withNetwork(network)
            .withEnv("ALLOW_PLAINTEXT_LISTENER", "yes")
            .withEnv("KAFKA_ENABLE_KRAFT", "yes")
            .withEnv("KAFKA_KRAFT_CLUSTER_ID", clusterId)
            .withEnv("KAFKA_HEAP_OPTS", "-Xms256m -Xmx512m")
            // 작은 격리 실험에서 cleaner의 기본 128MiB 버퍼가 broker heap을 소진하지 않게 한다.
            .withEnv("KAFKA_CFG_LOG_CLEANER_DEDUPE_BUFFER_SIZE", "16777216")
            .withEnv("KAFKA_CFG_NODE_ID", Integer.toString(id))
            .withEnv("KAFKA_CFG_PROCESS_ROLES", role)
            .withEnv("KAFKA_CFG_LISTENER_SECURITY_PROTOCOL_MAP",
                "INTERNAL:PLAINTEXT,EXTERNAL:PLAINTEXT,CONTROLLER:PLAINTEXT")
            .withEnv("KAFKA_CFG_INTER_BROKER_LISTENER_NAME", "INTERNAL")
            .withEnv("KAFKA_CFG_CONTROLLER_LISTENER_NAMES", "CONTROLLER")
            .withEnv("KAFKA_CFG_CONTROLLER_QUORUM_VOTERS", "100@controller:9093")
            .withEnv("KAFKA_CFG_AUTO_CREATE_TOPICS_ENABLE", "false")
            .withEnv("KAFKA_CFG_OFFSETS_TOPIC_REPLICATION_FACTOR", "3")
            .withEnv("KAFKA_CFG_TRANSACTION_STATE_LOG_REPLICATION_FACTOR", "3")
            .withEnv("KAFKA_CFG_TRANSACTION_STATE_LOG_MIN_ISR", "2")
            .withEnv("KAFKA_CFG_REPLICA_LAG_TIME_MAX_MS", "5000")
            .waitingFor(Wait.forLogMessage(".*Kafka Server started.*\\n", 1))
            .withStartupTimeout(Duration.ofSeconds(75));
    }

    public String bootstrapServers() {
        return brokers.stream().map(broker -> broker.getHost() + ":" + broker.getMappedPort(19092))
            .collect(Collectors.joining(","));
    }

    public void stopBroker(int id) {
        // GenericContainer.stop()과 달리 컨테이너·데이터를 삭제하지 않고 동일 노드를 재시작할 수 있다.
        DockerClientFactory.instance().client().stopContainerCmd(brokers.get(id - 1).getContainerId())
            .withTimeout(1).exec();
    }

    public void startBroker(int id) {
        DockerClientFactory.instance().client().startContainerCmd(brokers.get(id - 1).getContainerId()).exec();
    }

    public boolean controllerIsRunning() {
        return controller.isRunning();
    }

    public String diagnostics() {
        StringBuilder result = new StringBuilder();
        List<GenericContainer<?>> containers = new ArrayList<>(brokers);
        containers.add(controller);
        for (GenericContainer<?> container : containers) {
            String logs = container.getLogs();
            result.append("\n").append(container.getContainerId()).append(" running=")
                .append(container.isRunning()).append("\n")
                .append(logs.substring(Math.max(0, logs.length() - 6_000)));
        }
        return result.toString();
    }

    @Override
    public void close() {
        RuntimeException failure = null;
        List<GenericContainer<?>> containers = new ArrayList<>(brokers);
        containers.add(controller);
        for (GenericContainer<?> container : containers) {
            try {
                container.stop();
            } catch (RuntimeException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        try {
            network.close();
        } catch (RuntimeException exception) {
            if (failure == null) {
                failure = exception;
            } else {
                failure.addSuppressed(exception);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static int freePort() {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
