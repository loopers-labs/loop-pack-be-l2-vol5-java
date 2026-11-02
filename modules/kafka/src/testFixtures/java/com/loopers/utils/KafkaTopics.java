package com.loopers.utils;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.KafkaFuture;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * 테스트용 topic 을 고유 이름으로 만들고 자기가 만든 것만 지움.
 * topic 삭제는 비동기라 같은 이름을 바로 다시 만들면 실패할 수 있어, DatabaseCleanUp 처럼 비우지 않고 이름으로 격리함
 */
@Component
public class KafkaTopics {

    private static final long TIMEOUT_SECONDS = 10;

    private final KafkaAdmin kafkaAdmin;
    private final List<String> created = new CopyOnWriteArrayList<>();

    public KafkaTopics(KafkaAdmin kafkaAdmin) {
        this.kafkaAdmin = kafkaAdmin;
    }

    public String create(String prefix, int partitions) {
        String name = prefix + "-" + UUID.randomUUID();
        call(admin -> admin.createTopics(List.of(new NewTopic(name, partitions, (short) 1))).all());
        created.add(name);
        return name;
    }

    public int partitionCount(String topic) {
        return call(admin -> admin.describeTopics(List.of(topic)).allTopicNames())
            .get(topic).partitions().size();
    }

    public Set<String> names() {
        return call(admin -> admin.listTopics().names());
    }

    public void deleteCreated() {
        if (created.isEmpty()) {
            return;
        }
        call(admin -> admin.deleteTopics(List.copyOf(created)).all());
        created.clear();
    }

    private <T> T call(Function<AdminClient, KafkaFuture<T>> request) {
        try (AdminClient admin = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            return request.apply(admin).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("Kafka admin 요청 실패", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
