package com.loopers.testcontainers;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 테스트가 함께 쓰는 실제 Kafka broker 하나(단일 노드, KRaft). 처음 {@link #bootstrapServers()}를 부를 때만 컨테이너를 띄운다.
 * Kafka를 쓰지 않는 테스트는 컨테이너를 시작하지 않는다.
 *
 * <p>producer·consumer·admin은 모두 {@link #bootstrapServers()}가 돌려주는 호스트 주소 하나를 쓴다.
 * 컨테이너 안쪽 주소(kafka:9092)와 섞지 않는다. 정리는 Testcontainers의 reaper가 JVM 종료 때 맡는다.
 * 테스트는 {@link #createTopic}으로 고유한 topic을 만들어 서로 격리하고, 다른 실행의 topic·group을 지우지 않는다.
 */
public final class KafkaBrokerFixture {

    private static final String IMAGE = "apache/kafka:3.8.0";
    private static final long ADMIN_TIMEOUT_SECONDS = 30;

    private KafkaBrokerFixture() {
    }

    /** 컨테이너를 (필요하면 시작하고) 호스트에서 접근하는 bootstrap 주소를 돌려준다. */
    public static String bootstrapServers() {
        return Holder.BROKER.getBootstrapServers();
    }

    /** 접두사에 무작위 접미사를 붙인 이름. 같은 broker를 쓰는 테스트끼리 topic·group이 겹치지 않는다. */
    public static String uniqueName(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** 고유한 이름의 topic을 만들고 이름을 돌려준다. 생성이 끝날 때까지 기다린다. */
    public static String createTopic(String prefix, int partitions) {
        String topic = uniqueName(prefix);
        try (AdminClient admin = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(topic, partitions, (short) 1)))
                .all()
                .get(ADMIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("topic 생성을 기다리다 중단됐습니다: " + topic, e);
        } catch (Exception e) {
            throw new IllegalStateException("topic을 만들지 못했습니다: " + topic, e);
        }
        return topic;
    }

    // 클래스를 처음 쓸 때가 아니라 bootstrapServers()를 처음 부를 때 컨테이너를 띄우려고 홀더로 감싼다
    private static final class Holder {
        private static final KafkaContainer BROKER = new KafkaContainer(DockerImageName.parse(IMAGE));

        static {
            BROKER.start();
        }
    }
}
