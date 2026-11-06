package com.loopers.support.kafka;

import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.consumer.OffsetCommitCallback;
import org.apache.kafka.common.TopicPartition;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * offset commit·seek 호출 이력을 남기는 low-level consumer. "처리는 끝났지만 offset은 commit하지 않았다"를 로그가 아니라 호출 이력으로 확인한다.
 * 이력만 더할 뿐 동작은 {@link KafkaConsumer} 그대로다.
 */
public final class RecordingConsumer extends KafkaConsumer<String, byte[]> {

    /** commit을 요청한 offset들. 호출마다 {partition → 다음에 읽을 offset} 한 건. */
    private final List<Map<TopicPartition, Long>> explicitCommits = new CopyOnWriteArrayList<>();
    private final AtomicInteger otherCommitCalls = new AtomicInteger();
    private final AtomicInteger seekCalls = new AtomicInteger();

    public RecordingConsumer(Map<String, Object> configs) {
        super(configs);
    }

    public List<Map<TopicPartition, Long>> explicitCommits() {
        return List.copyOf(explicitCommits);
    }

    /** 위치를 지정하지 않은 commit(현재 위치를 commit)과 비동기 commit의 호출 수. 0이어야 한다. */
    public int otherCommitCalls() {
        return otherCommitCalls.get();
    }

    public int seekCalls() {
        return seekCalls.get();
    }

    @Override
    public void commitSync() {
        otherCommitCalls.incrementAndGet();
        super.commitSync();
    }

    @Override
    public void commitSync(Duration timeout) {
        otherCommitCalls.incrementAndGet();
        super.commitSync(timeout);
    }

    @Override
    public void commitSync(Map<TopicPartition, OffsetAndMetadata> offsets) {
        record(offsets);
        super.commitSync(offsets);
    }

    @Override
    public void commitSync(Map<TopicPartition, OffsetAndMetadata> offsets, Duration timeout) {
        record(offsets);
        super.commitSync(offsets, timeout);
    }

    @Override
    public void commitAsync() {
        otherCommitCalls.incrementAndGet();
        super.commitAsync();
    }

    @Override
    public void commitAsync(OffsetCommitCallback callback) {
        otherCommitCalls.incrementAndGet();
        super.commitAsync(callback);
    }

    @Override
    public void commitAsync(Map<TopicPartition, OffsetAndMetadata> offsets, OffsetCommitCallback callback) {
        otherCommitCalls.incrementAndGet();
        super.commitAsync(offsets, callback);
    }

    @Override
    public void seek(TopicPartition partition, long offset) {
        seekCalls.incrementAndGet();
        super.seek(partition, offset);
    }

    @Override
    public void seek(TopicPartition partition, OffsetAndMetadata offsetAndMetadata) {
        seekCalls.incrementAndGet();
        super.seek(partition, offsetAndMetadata);
    }

    @Override
    public void seekToBeginning(Collection<TopicPartition> partitions) {
        seekCalls.incrementAndGet();
        super.seekToBeginning(partitions);
    }

    @Override
    public void seekToEnd(Collection<TopicPartition> partitions) {
        seekCalls.incrementAndGet();
        super.seekToEnd(partitions);
    }

    private void record(Map<TopicPartition, OffsetAndMetadata> offsets) {
        Map<TopicPartition, Long> positions = new java.util.HashMap<>();
        offsets.forEach((partition, metadata) -> positions.put(partition, metadata.offset()));
        explicitCommits.add(positions);
    }
}
