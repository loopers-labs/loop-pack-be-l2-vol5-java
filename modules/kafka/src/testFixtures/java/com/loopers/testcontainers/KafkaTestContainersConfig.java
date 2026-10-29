package com.loopers.testcontainers;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Configuration;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Configuration
public class KafkaTestContainersConfig {

    public static final String ORDER_EVENTS = "order-events";

    private static final int ORDER_EVENTS_PARTITIONS = 3;
    private static final short REPLICATION_FACTOR = 1;

    private static final ConfluentKafkaContainer kafkaContainer =
        new ConfluentKafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.5.0"))
            .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");

    static {
        kafkaContainer.start();
        createTopic(ORDER_EVENTS, ORDER_EVENTS_PARTITIONS);
        System.setProperty("spring.kafka.bootstrap-servers", kafkaContainer.getBootstrapServers());
    }

    private static void createTopic(String name, int partitions) {
        Map<String, Object> config = Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaContainer.getBootstrapServers());
        try (Admin admin = Admin.create(config)) {
            admin.createTopics(List.of(new NewTopic(name, partitions, REPLICATION_FACTOR)))
                .all()
                .get(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("테스트 broker 에 " + name + " 을 만들다 중단됐다", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("테스트 broker 에 " + name + " 을 만들지 못했다", e);
        }
    }
}
