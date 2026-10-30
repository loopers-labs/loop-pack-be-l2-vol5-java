package com.loopers.testcontainers;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 테스트가 붙는 Kafka broker(docs/week6/decisions.md D-24). JVM마다 하나만 띄운다 — MySQL·Redis 공용 픽스처와 같은 방식이다.
 * modules/kafka의 testFixtures에는 소스가 없고 modules/*는 수정하지 않는 규칙이라 앱 쪽에 둔다.
 *
 * <p>주소는 {@link KafkaTestContainersInitializer}가 producer·admin 모두에 컨테이너의 실제 주소로 넣는다
 * (kafka.yml test 프로필의 localhost:19092·kafka:9092를 섞지 않는다).
 * 업무 topic(order-events)은 띄울 때 만들어 둔다 — 다른 테스트의 결제 확정도 발행하기 때문이다.
 * 발행을 검증하는 테스트는 {@link #createTopic}으로 고유 suffix topic을 따로 만든다.
 */
public final class KafkaTestContainers {

    public static final String DEFAULT_TOPIC = "order-events";
    public static final int PARTITIONS = 2;

    private static final KafkaContainer CONTAINER = start();

    private KafkaTestContainers() {}

    private static KafkaContainer start() {
        KafkaContainer container = new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.1"));
        container.start();
        createTopic(container.getBootstrapServers(), DEFAULT_TOPIC);
        return container;
    }

    public static String bootstrapServers() {
        return CONTAINER.getBootstrapServers();
    }

    public static void createTopic(String topic) {
        createTopic(bootstrapServers(), topic);
    }

    private static void createTopic(String bootstrapServers, String topic) {
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers))) {
            admin.createTopics(List.of(new NewTopic(topic, PARTITIONS, (short) 1))).all().get(30, TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("테스트 topic 생성 실패: " + topic, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("테스트 topic 생성 중단: " + topic, e);
        }
    }
}
