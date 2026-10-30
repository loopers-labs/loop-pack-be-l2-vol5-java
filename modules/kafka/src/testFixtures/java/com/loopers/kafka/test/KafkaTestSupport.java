package com.loopers.kafka.test;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.serialization.StringDeserializer;

// 앱 테스트가 함께 쓰는 Kafka 일반 도우미. 업무 메시지 해석은 각 앱 테스트에 둔다.
// 마감 시간은 하나(30초)다. 정상이면 조건이 맞는 즉시 끝나므로 마감 시간은 느린 환경에서 거짓 실패를 줄이는 상한일 뿐이다.
public final class KafkaTestSupport {
    public static final Duration DEADLINE = Duration.ofSeconds(30);
    public static final Duration POLL_TIMEOUT = Duration.ofMillis(100);
    private static final Duration RECHECK_INTERVAL = Duration.ofMillis(20);
    private static final long ADMIN_TIMEOUT_SECONDS = 10;

    private KafkaTestSupport() {
    }

    public static String uniqueName(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    public static Admin admin(String bootstrapServers) {
        return Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers));
    }

    public static void createTopic(Admin admin, String topic, int partitions) throws Exception {
        admin.createTopics(List.of(new NewTopic(topic, partitions, (short) 1)))
            .all()
            .get(ADMIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    public static void createTopicIfAbsent(String bootstrapServers, String topic, int partitions) {
        try (Admin admin = admin(bootstrapServers)) {
            createTopic(admin, topic, partitions);
        } catch (ExecutionException e) {
            if (!(e.getCause() instanceof TopicExistsException)) {
                throw new IllegalStateException("테스트 topic을 만들지 못했습니다. topic=" + topic, e);
            }
        } catch (Exception e) {
            throw new IllegalStateException("테스트 topic을 만들지 못했습니다. topic=" + topic, e);
        }
    }

    // 문자열 key·값을 읽고 자동 commit은 끈다. 실험별 설정은 돌려받은 Map에 덧붙인다.
    public static Map<String, Object> consumerConfig(String bootstrapServers, String groupId) {
        Map<String, Object> config = new HashMap<String, Object>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        return config;
    }

    public static Long committedOffset(Admin admin, String groupId, TopicPartition partition) throws Exception {
        Map<TopicPartition, OffsetAndMetadata> offsets = admin.listConsumerGroupOffsets(groupId)
            .partitionsToOffsetAndMetadata()
            .get(ADMIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        OffsetAndMetadata committed = offsets.get(partition);
        if (committed == null) {
            return null;
        }
        return committed.offset();
    }

    // 배정·fetch는 poll 안에서 진행되므로 condition이 poll을 부르면 그 안에서 기다린다.
    // DB·admin 조회처럼 바로 돌아오는 condition이 쉬지 않고 반복되지 않게 확인 사이에 잠깐 쉰다.
    public static void awaitUntil(String description, BooleanSupplier condition) {
        Instant deadline = Instant.now().plus(DEADLINE);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }
            LockSupport.parkNanos(RECHECK_INTERVAL.toNanos());
        }
        throw new AssertionError(DEADLINE.toSeconds() + "초 안에 도달하지 못함: " + description);
    }

    public static void closeAll(List<? extends AutoCloseable> closeables) {
        IllegalStateException failure = null;
        for (AutoCloseable closeable : closeables) {
            try {
                closeable.close();
            } catch (Exception e) {
                if (failure == null) {
                    failure = new IllegalStateException("Kafka 클라이언트 종료 실패", e);
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
