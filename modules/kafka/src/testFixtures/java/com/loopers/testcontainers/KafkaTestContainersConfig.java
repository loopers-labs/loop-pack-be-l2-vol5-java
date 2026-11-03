package com.loopers.testcontainers;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.errors.TopicExistsException;
import org.springframework.context.annotation.Configuration;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * 테스트용 단일 broker. MySqlTestContainersConfig 와 같은 방식으로 클래스 초기화 때 컨테이너를 띄우고
 * 주소를 시스템 속성으로 주입한다. JVM 당 broker 1개를 모든 테스트가 공유하므로 격리는 고유 topic·group 으로 한다.
 */
@Configuration
public class KafkaTestContainersConfig {

    private static final KafkaContainer kafkaContainer;

    static {
        kafkaContainer = new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.1"));
        kafkaContainer.start();

        // producer·consumer·admin 이 같은 테스트 broker 를 쓰게 한다. test profile 의 admin 주소(kafka:9092)는
        // 컨테이너 내부 주소라 호스트에서 해석되지 않으므로 시스템 속성으로 덮는다 (시스템 속성이 yml 보다 우선).
        System.setProperty("spring.kafka.bootstrap-servers", kafkaContainer.getBootstrapServers());
        System.setProperty("spring.kafka.admin.properties.bootstrap.servers", kafkaContainer.getBootstrapServers());

        // 앱은 topic 을 만들지 않으므로(설계 7절 결정) 기본 업무 topic 은 fixture 가 미리 만든다. broker auto-create 에 기대지 않는다.
        createTopic("order-events", 1);
    }

    /** 격리 topic 이 필요한 테스트도 같은 방법으로 만든다. 이미 있으면 그대로 둔다. */
    public static void createTopic(String topic, int partitions) {
        try (AdminClient admin = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(topic, partitions, (short) 1))).all().get(10, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (!(e.getCause() instanceof TopicExistsException)) {
                throw new IllegalStateException("topic 생성 실패: " + topic, e);
            }
        } catch (Exception e) {
            throw new IllegalStateException("topic 생성 실패: " + topic, e);
        }
    }

    /** Spring 밖에서 KafkaConsumer·AdminClient 를 직접 만들 때 쓰는 실제 host 주소. */
    public static String bootstrapServers() {
        return kafkaContainer.getBootstrapServers();
    }
}
