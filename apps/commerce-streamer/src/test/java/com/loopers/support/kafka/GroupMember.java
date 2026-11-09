package com.loopers.support.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 같은 group에 참여하는 consumer 하나. 자기 스레드에서 계속 poll하며 group에 실제로 참여한다
 * (KafkaConsumer는 스레드 안전하지 않아 객체를 만든 스레드가 아니라 poll하는 스레드가 소유한다).
 * 받은 record는 member별로 모아 두고, 처리 담당자(어느 member가 읽었는가)를 확인하는 데 쓴다.
 */
public final class GroupMember implements AutoCloseable {

    public record Received(int partition, long offset, String key, String value) {
    }

    private final String clientId;
    private final Thread thread;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final ConcurrentLinkedQueue<Received> received = new ConcurrentLinkedQueue<>();
    private volatile Set<Integer> assignedPartitions = Set.of();

    public GroupMember(String groupId, String clientId, String topic) {
        this.clientId = clientId;
        Map<String, Object> props = KafkaLab.consumerProps(groupId, clientId, "earliest");
        this.thread = new Thread(() -> pollLoop(props, topic), "group-member-" + clientId);
        this.thread.start();
    }

    private void pollLoop(Map<String, Object> props, String topic) {
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(topic));
            while (running.get()) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(200))) {
                    received.add(new Received(record.partition(), record.offset(), record.key(), record.value()));
                }
                Set<Integer> partitions = new TreeSet<>();
                for (TopicPartition tp : consumer.assignment()) {
                    partitions.add(tp.partition());
                }
                assignedPartitions = partitions;
            }
            // try-with-resources가 close()를 부른다: LeaveGroup을 보내 남은 member가 바로 다시 배정받게 한다
        }
    }

    public String clientId() {
        return clientId;
    }

    /** 이 member가 자기 consumer에서 본 배정 (poll 스레드가 갱신). */
    public Set<Integer> assignedPartitions() {
        return assignedPartitions;
    }

    public List<Received> received() {
        return List.copyOf(received);
    }

    @Override
    public void close() {
        running.set(false);
        try {
            thread.join(Duration.ofSeconds(15).toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
