package com.loopers.support;

import com.loopers.testcontainers.KafkaTestContainers;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 소비 테스트가 쓰는 Kafka 쪽 도구: 기록 보내기(producer 흉내), group의 committed offset 읽기, 계약 모양의 JSON 만들기.
 *
 * <p>committed offset은 position이나 로그가 아니라 admin으로 group의 값을 읽는다(docs/week6/decisions.md D-21).
 * 테스트마다 고유 ID(D-13)는 {@link #nextId()}로 받는다 — 시작 값을 실행 시각에서 잡아, 이전 실행이 남긴 topic·DB 흔적과도 겹치지 않는다.
 */
public final class KafkaProbe implements AutoCloseable {

    private static final AtomicLong IDS = new AtomicLong(System.currentTimeMillis() * 1_000);
    private static final DateTimeFormatter CONTRACT_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'");

    private final String topic;
    private final String groupId;
    private final KafkaProducer<String, byte[]> producer;
    private final Admin admin;

    public KafkaProbe(String topic, String groupId) {
        this.topic = topic;
        this.groupId = groupId;
        this.producer = new KafkaProducer<>(Map.of(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestContainers.bootstrapServers(),
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class,
            ProducerConfig.ACKS_CONFIG, "all"));
        this.admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestContainers.bootstrapServers()));
    }

    /** 고유 suffix topic·group 이름. */
    public static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    public static long nextId() {
        return IDS.incrementAndGet();
    }

    public static String json(long orderId, ZonedDateTime confirmedAt, long[]... productAndQuantity) {
        return json("order-confirmed:v1:" + orderId, orderId, confirmedAt, productAndQuantity);
    }

    public static String json(String eventId, long orderId, ZonedDateTime confirmedAt, long[]... productAndQuantity) {
        String items = Stream.of(productAndQuantity)
            .map(pq -> "{\"productId\":" + pq[0] + ",\"quantity\":" + pq[1] + "}")
            .collect(Collectors.joining(","));
        return "{\"eventType\":\"OrderConfirmed\",\"schemaVersion\":1,\"eventId\":\"" + eventId + "\",\"orderId\":" + orderId
            + ",\"confirmedAt\":\"" + confirmedAt.withZoneSameInstant(ZoneOffset.UTC).format(CONTRACT_TIME)
            + "\",\"items\":[" + items + "]}";
    }

    public static long[] item(long productId, long quantity) {
        return new long[] {productId, quantity};
    }

    public RecordMetadata send(String key, String value) {
        return send(null, key, value.getBytes(StandardCharsets.UTF_8));
    }

    /** partition이 null이면 producer가 key로 고른다. 보낸 뒤 broker 기록(acks=all)까지 기다린다. */
    public RecordMetadata send(Integer partition, String key, byte[] value) {
        try {
            return producer.send(new ProducerRecord<>(topic, partition, key, value)).get(10, TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("테스트 기록 전송 실패", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("테스트 기록 전송 중단", e);
        }
    }

    /** group의 committed offset("다음에 읽을 위치"). 아직 commit이 없으면 -1. */
    public long committed(int partition) {
        try {
            Map<TopicPartition, OffsetAndMetadata> offsets = admin.listConsumerGroupOffsets(groupId)
                .partitionsToOffsetAndMetadata().get(10, TimeUnit.SECONDS);
            OffsetAndMetadata offset = offsets.get(new TopicPartition(topic, partition));
            return offset == null ? -1 : offset.offset();
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("committed offset 조회 실패", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("committed offset 조회 중단", e);
        }
    }

    @Override
    public void close() {
        producer.close();
        admin.close();
    }
}
