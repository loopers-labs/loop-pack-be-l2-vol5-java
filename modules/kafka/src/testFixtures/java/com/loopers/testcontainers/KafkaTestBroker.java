package com.loopers.testcontainers;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.time.Duration;

/** 로컬 Compose와 같은 Kafka 3.5.1 KRaft. 명시적으로 생성한 테스트에서만 시작하고 종료한다. */
public final class KafkaTestBroker implements AutoCloseable {
    private final GenericContainer<?> broker;

    public KafkaTestBroker() {
        int externalPort = freePort();
        String host = DockerClientFactory.instance().dockerHostIpAddress();
        broker = new GenericContainer<>(DockerImageName.parse("bitnamilegacy/kafka:3.5.1"))
            .withExposedPorts(19092)
            .withCreateContainerCmdModifier(command -> command.getHostConfig().withPortBindings(
                new PortBinding(Ports.Binding.bindPort(externalPort), new ExposedPort(19092))))
            .withEnv("ALLOW_PLAINTEXT_LISTENER", "yes")
            .withEnv("KAFKA_ENABLE_KRAFT", "yes")
            .withEnv("KAFKA_CFG_NODE_ID", "1")
            .withEnv("KAFKA_CFG_PROCESS_ROLES", "broker,controller")
            .withEnv("KAFKA_CFG_LISTENERS", "INTERNAL://:9092,EXTERNAL://:19092,CONTROLLER://:9093")
            .withEnv("KAFKA_CFG_ADVERTISED_LISTENERS",
                "INTERNAL://localhost:9092,EXTERNAL://" + host + ":" + externalPort)
            .withEnv("KAFKA_CFG_LISTENER_SECURITY_PROTOCOL_MAP",
                "INTERNAL:PLAINTEXT,EXTERNAL:PLAINTEXT,CONTROLLER:PLAINTEXT")
            .withEnv("KAFKA_CFG_INTER_BROKER_LISTENER_NAME", "INTERNAL")
            .withEnv("KAFKA_CFG_CONTROLLER_LISTENER_NAMES", "CONTROLLER")
            .withEnv("KAFKA_CFG_CONTROLLER_QUORUM_VOTERS", "1@localhost:9093")
            .withEnv("KAFKA_CFG_OFFSETS_TOPIC_REPLICATION_FACTOR", "1")
            .withEnv("KAFKA_CFG_TRANSACTION_STATE_LOG_REPLICATION_FACTOR", "1")
            .withEnv("KAFKA_CFG_TRANSACTION_STATE_LOG_MIN_ISR", "1")
            .withEnv("KAFKA_CFG_AUTO_CREATE_TOPICS_ENABLE", "false")
            .waitingFor(Wait.forLogMessage(".*Kafka Server started.*\\n", 1))
            .withStartupTimeout(Duration.ofSeconds(90));
        broker.start();
    }

    public String bootstrapServers() {
        return broker.getHost() + ":" + broker.getMappedPort(19092);
    }

    /** broker 프로세스를 얼린다. 포트와 연결은 남지만 응답하지 않는다. 재시작과 달리 주소가 바뀌지 않는다. */
    public void pause() {
        DockerClientFactory.instance().client().pauseContainerCmd(broker.getContainerId()).exec();
    }

    /** {@link #pause()}로 얼린 broker를 다시 응답하게 한다. */
    public void resume() {
        DockerClientFactory.instance().client().unpauseContainerCmd(broker.getContainerId()).exec();
    }

    /**
     * broker 프로세스를 내린다. {@link #pause()}와 달리 연결을 거부하므로, 보내던 요청이 broker에 남지 않는다.
     * 컨테이너와 포트 지정은 그대로라 {@link #startAgain()} 뒤 같은 주소로 다시 붙는다.
     */
    public void stopProcess() {
        DockerClientFactory.instance().client().stopContainerCmd(broker.getContainerId()).exec();
    }

    /** {@link #stopProcess()}로 내린 broker를 같은 컨테이너·주소로 다시 올린다. 준비 완료는 호출자가 확인한다. */
    public void startAgain() {
        DockerClientFactory.instance().client().startContainerCmd(broker.getContainerId()).exec();
    }

    @Override
    public void close() {
        broker.stop();
    }

    private static int freePort() {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
