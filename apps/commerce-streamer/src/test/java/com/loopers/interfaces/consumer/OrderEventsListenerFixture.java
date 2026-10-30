package com.loopers.interfaces.consumer;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;

import static com.loopers.kafka.KafkaLearningSupport.producer;
import static com.loopers.kafka.test.KafkaTestSupport.admin;
import static com.loopers.kafka.test.KafkaTestSupport.awaitUntil;
import static com.loopers.kafka.test.KafkaTestSupport.closeAll;
import static com.loopers.kafka.test.KafkaTestSupport.committedOffset;

// 실제 listener 컨테이너를 관찰하고 테스트 record를 topic에 직접 넣는 도구. 공개 endpoint 없이 Kafka로만 이벤트를 보낸다.
final class OrderEventsListenerFixture implements AutoCloseable {

    static final String GROUP_ID = "purchase-activity";

    private final String topic;
    private final Admin admin;
    private final KafkaProducer<String, String> producer;
    private final MessageListenerContainer container;

    OrderEventsListenerFixture(String bootstrapServers, String topic, KafkaListenerEndpointRegistry registry) {
        this.topic = topic;
        this.admin = admin(bootstrapServers);
        this.producer = producer(bootstrapServers);
        this.container = registry.getListenerContainer(OrderEventsListener.LISTENER_ID);
    }

    MessageListenerContainer container() {
        return container;
    }

    // offset reset 설정에 기대지 않고 배정이 끝난 뒤 보낸다. 설정이 바뀌어도 테스트가 타이밍 문제를 가리지 않게 하기 위해서다.
    void awaitAssigned() {
        awaitUntil("order-events listener 배정", () -> {
            Collection<TopicPartition> assigned = container.getAssignedPartitions();
            return container.isRunning() && assigned != null && !assigned.isEmpty();
        });
    }

    long send(String key, String json) throws Exception {
        return producer.send(new ProducerRecord<>(topic, key, json)).get(10, TimeUnit.SECONDS).offset();
    }

    Long committed() {
        try {
            return committedOffset(admin, GROUP_ID, new TopicPartition(topic, 0));
        } catch (Exception e) {
            throw new IllegalStateException("committed offset 조회 실패", e);
        }
    }

    void awaitCommitted(long expected) {
        awaitUntil("committed offset " + expected, () -> Long.valueOf(expected).equals(committed()));
    }

    void awaitStopped() {
        awaitUntil("listener 컨테이너 정지", () -> !container.isRunning());
    }

    @Override
    public void close() {
        closeAll(List.of(producer, admin));
    }
}
