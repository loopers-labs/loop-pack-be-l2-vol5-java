package com.loopers.support;

import com.loopers.testcontainers.KafkaTestContainersConfig;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * 테스트 broker(KafkaTestContainersConfig)를 직접 다룬다: topic 생성, record 전송(실제 metadata 반환), group 의 committed offset 조회, 유한 대기.
 * topic·group 은 호출하는 테스트가 고유 이름으로 정한다. 다른 실행의 topic·group 은 지우지 않는다 (컨테이너는 JVM 종료 시 정리).
 */
public final class KafkaTestSupport {
    public static final Duration TIMEOUT = Duration.ofSeconds(30);

    private KafkaTestSupport() {
    }

    public static String bootstrapServers() {
        return KafkaTestContainersConfig.bootstrapServers();
    }

    public static void createTopic(String topic, int partitions) {
        try (Admin admin = admin()) {
            admin.createTopics(List.of(new NewTopic(topic, partitions, (short) 1))).all().get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("topic 생성 실패 [topic = " + topic + "]", e);
        }
    }

    /** producer 가 partition 을 고르게 보낸다 (partition = null 이면 key 로). broker 가 기록한 실제 위치를 돌려준다. */
    public static RecordMetadata send(String topic, Integer partition, String key, String value) {
        try (KafkaProducer<String, byte[]> producer = new KafkaProducer<>(Map.of(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers(),
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class,
            ProducerConfig.ACKS_CONFIG, "all"
        ))) {
            return producer.send(new ProducerRecord<>(topic, partition, key, value.getBytes(StandardCharsets.UTF_8)))
                .get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("전송 실패 [topic = " + topic + ", key = " + key + "]", e);
        }
    }

    /** group 이 commit 한 다음 읽을 위치. commit 이 없으면 null. */
    public static Long committedOffset(String groupId, TopicPartition partition) {
        try (Admin admin = admin()) {
            OffsetAndMetadata committed = admin.listConsumerGroupOffsets(groupId).partitionsToOffsetAndMetadata()
                .get(TIMEOUT.toSeconds(), TimeUnit.SECONDS).get(partition);
            return committed == null ? null : committed.offset();
        } catch (Exception e) {
            throw new IllegalStateException("committed offset 조회 실패 [group = " + groupId + "]", e);
        }
    }

    /** 조건이 참이 될 때까지 유한하게 기다린다. 시간 안에 안 되면 실패. */
    public static void await(String what, BooleanSupplier condition) {
        Instant deadline = Instant.now().plus(TIMEOUT);
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("시간 안에 되지 않음: " + what);
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("대기 중단: " + what, e);
            }
        }
    }

    private static Admin admin() {
        return Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers()));
    }
}
