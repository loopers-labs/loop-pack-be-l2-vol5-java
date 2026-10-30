package com.loopers.support.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;

import java.time.Duration;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;

/** 자기 스레드에서 계속 poll 해 group 에 참여하는 member. 받은 record 를 모아 둔다. */
public final class GroupMember implements AutoCloseable {

    private final String clientId;
    private final Queue<ConsumerRecord<String, byte[]>> received = new ConcurrentLinkedQueue<>();
    private final Thread thread;
    private volatile boolean running = true;

    GroupMember(ExperimentTopic topic, String clientId) {
        this.clientId = clientId;
        KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(topic.consumerProps(clientId, "earliest"));
        consumer.subscribe(List.of(topic.name()));
        this.thread = new Thread(() -> {
            try {
                while (running) {
                    consumer.poll(Duration.ofMillis(100)).forEach(received::add);
                }
            } finally {
                consumer.close(Duration.ofSeconds(5));
            }
        }, "member-" + clientId);
        thread.start();
    }

    public String clientId() {
        return clientId;
    }

    public List<ConsumerRecord<String, byte[]>> received() {
        return List.copyOf(received);
    }

    public Set<Integer> receivedPartitions() {
        return received().stream().map(ConsumerRecord::partition).collect(Collectors.toCollection(TreeSet::new));
    }

    @Override
    public void close() {
        running = false;
        try {
            thread.join(15_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
