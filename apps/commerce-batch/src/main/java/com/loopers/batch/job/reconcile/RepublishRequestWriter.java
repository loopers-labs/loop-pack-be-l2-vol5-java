package com.loopers.batch.job.reconcile;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

// 청크의 명령을 모두 보낸 뒤 확인을 기다려 producer가 묶어 보내게 한다. 확인을 전부 받은 뒤에만 워터마크를 청크 끝으로 옮기고,
// 하나라도 실패하면 예외로 step을 실패시켜 워터마크를 마지막 성공 청크에 남긴다(설계 27절).
public class RepublishRequestWriter implements ItemWriter<MissingOrder> {
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final String topic;
    private final ReconcileCheckpointRepository checkpointRepository;
    private final ReconcileRun run;
    private final Clock clock;
    private final Duration sendTimeout;

    public RepublishRequestWriter(KafkaTemplate<String, String> kafkaTemplate, String topic,
        ReconcileCheckpointRepository checkpointRepository, ReconcileRun run, Clock clock, Duration sendTimeout) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
        this.checkpointRepository = checkpointRepository;
        this.run = run;
        this.clock = clock;
        this.sendTimeout = sendTimeout;
    }

    @Override
    public void write(Chunk<? extends MissingOrder> chunk) throws Exception {
        List<? extends MissingOrder> orders = chunk.getItems();
        run.found(orders.stream().map(MissingOrder::orderId).toList());
        Instant requestedAt = clock.instant();
        List<CompletableFuture<SendResult<String, String>>> results = orders.stream()
            .map(order -> kafkaTemplate.send(topic, String.valueOf(order.orderId()),
                RepublishCommandSerializer.serialize(order.orderId(), requestedAt)))
            .toList();
        for (int index = 0; index < results.size(); index++) {
            try {
                results.get(index).get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (ExecutionException | TimeoutException e) {
                throw new IllegalStateException("재발행 명령 send 확인 실패. orderId=" + orders.get(index).orderId(), e);
            }
        }
        run.sent(orders.size());
        MissingOrder last = orders.getLast();
        checkpointRepository.save(new ReconcileCheckpoint(last.confirmedAt(), last.orderId()), clock.instant());
    }
}
