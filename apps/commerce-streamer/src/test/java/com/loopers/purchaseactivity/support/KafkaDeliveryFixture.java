package com.loopers.purchaseactivity.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.loopers.kafka.event.OrderConfirmed;
import com.loopers.testcontainers.KafkaTestContainersConfig;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.GroupIdNotFoundException;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

public final class KafkaDeliveryFixture implements AutoCloseable {
    private final String topic;
    private final String group;
    private final AdminClient admin;
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public KafkaDeliveryFixture(String label) {
        String suffix = UUID.randomUUID().toString();
        topic = "w6-" + label + "-" + suffix;
        group = "w6-" + label + "-group-" + suffix;
        admin = AdminClient.create(Map.of("bootstrap.servers", bootstrapServers()));
        try {
            admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get(10, TimeUnit.SECONDS);
        } catch (Exception failure) {
            admin.close(Duration.ofSeconds(5));
            throw new IllegalStateException("Could not create isolated Kafka topic", failure);
        }
    }

    public String topic() {
        return topic;
    }

    public String group() {
        return group;
    }

    public String bootstrapServers() {
        return KafkaTestContainersConfig.bootstrapServers();
    }

    public RecordMetadata publish(OrderConfirmed event) throws Exception {
        return publishBytes(Long.toString(event.orderId()), mapper.writeValueAsBytes(event));
    }

    public RecordMetadata publishJson(String key, String json) throws Exception {
        return publishBytes(key, json.getBytes(StandardCharsets.UTF_8));
    }

    private RecordMetadata publishBytes(String key, byte[] value) throws Exception {
        var producer = new KafkaProducer<String, byte[]>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class,
                ProducerConfig.ACKS_CONFIG, "all",
                ProducerConfig.MAX_BLOCK_MS_CONFIG, 10_000,
                ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 3_000,
                ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 10_000));
        try {
            return producer.send(new ProducerRecord<>(topic, key, value)).get(10, TimeUnit.SECONDS);
        } finally {
            producer.close(Duration.ofSeconds(5));
        }
    }

    public KafkaConsumer<String, byte[]> consumer() {
        return new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, group,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class,
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 1));
    }

    public void joinGroupAndCommitStartingOffset(KafkaConsumer<String, byte[]> consumer, RecordMetadata target) {
        consumer.subscribe(List.of(topic));
        var partition = partition(target);
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (!consumer.assignment().contains(partition) && System.nanoTime() < deadline) {
            consumer.poll(Duration.ofMillis(200));
        }
        if (!consumer.assignment().contains(partition)) {
            throw new AssertionError("Consumer did not join its group in time");
        }
        consumer.seek(partition, target.offset());
        consumer.commitSync(Map.of(partition, new OffsetAndMetadata(target.offset())), Duration.ofSeconds(10));
    }

    public void commitStartingOffsetAndCloseConsumer(RecordMetadata target) {
        var consumer = consumer();
        try {
            joinGroupAndCommitStartingOffset(consumer, target);
        } finally {
            consumer.close(Duration.ofSeconds(5));
        }
    }

    public ConsumerRecord<String, byte[]> readOne(KafkaConsumer<String, byte[]> consumer) {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            for (ConsumerRecord<String, byte[]> record : consumer.poll(Duration.ofMillis(200))) {
                return record;
            }
        }
        throw new AssertionError("20초 안에 Kafka 메시지를 받지 못했습니다.");
    }

    public Long committedOffset(RecordMetadata target) throws Exception {
        var offsets = admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get(10, TimeUnit.SECONDS);
        var offset = offsets.get(partition(target));
        return offset == null ? null : offset.offset();
    }

    public TopicPartition partition(RecordMetadata target) {
        return new TopicPartition(target.topic(), target.partition());
    }

    @Override
    public void close() throws Exception {
        try {
            admin.deleteTopics(List.of(topic)).all().get(10, TimeUnit.SECONDS);
            try {
                admin.deleteConsumerGroups(List.of(group)).all().get(10, TimeUnit.SECONDS);
            } catch (ExecutionException failure) {
                if (!(failure.getCause() instanceof GroupIdNotFoundException)) {
                    throw failure;
                }
            }
        } finally {
            admin.close(Duration.ofSeconds(5));
        }
    }
}
