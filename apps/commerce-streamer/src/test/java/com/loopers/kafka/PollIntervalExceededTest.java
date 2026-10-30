package com.loopers.kafka;

import com.loopers.confg.kafka.KafkaConfig;
import com.loopers.purchase.adapter.in.kafka.OrderConfirmedMessageParser;
import com.loopers.purchase.application.port.in.PurchaseActivityUseCase;
import com.loopers.purchase.application.port.in.PurchaseActivityUseCase.Result;
import com.loopers.support.KafkaTestSupport;
import com.loopers.support.StreamerIntegrationTestSupport;
import com.loopers.testcontainers.KafkaTestContainersConfig;
import org.apache.kafka.clients.consumer.CommitFailedException;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.loopers.support.KafkaTestSupport.orderConfirmedJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * E-8 — 처리 한 번이 max.poll.interval.ms보다 오래 걸리면 consumer가 group에서 빠지고, 같은 record가 다른 consumer에게 간다.
 * <p>
 * 같은 group에 consumer 두 개(대기 중인 member가 있는 운영 상황). 실험 전용 topic(partition 1)이라 한 consumer만 배정받는다.
 * target record를 먼저 받은 consumer가 "느린" 쪽이 되어 15초 뒤에 처리한다. max.poll.interval.ms는 이 실험의 consumer만 10초.
 * heartbeat 스레드는 살아 있다 — 빠지는 이유는 session timeout이 아니라 poll 간격이다.
 * 처리는 애플리케이션 listener와 같은 파싱·멱등 handler를 쓴다. 업무 topic·group과 섞이지 않는다.
 */
class PollIntervalExceededTest extends StreamerIntegrationTestSupport {

    private static final String AT = "2026-10-28T14:03:12.123456+09:00";
    private static final int MAX_POLL_INTERVAL_MS = 10_000;
    private static final long SLOW_PROCESSING_MS = 15_000;

    @Autowired
    @Qualifier(KafkaConfig.STRING_TEMPLATE)
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private OrderConfirmedMessageParser parser;

    @Autowired
    private PurchaseActivityUseCase purchaseActivityUseCase;

    private final List<String> timeline = new CopyOnWriteArrayList<>();
    private volatile long t0;

    private void note(String who, String what) {
        String line = String.format("[E-8] t=%6.1fs | %-6s | %s", (System.currentTimeMillis() - t0) / 1000.0, who, what);
        timeline.add(line);
        System.out.println(line);
    }

    @DisplayName("E-8 처리가 max.poll.interval.ms(10s)를 넘기면 그 consumer는 group에서 빠지고, 다른 consumer가 committed offset n부터 같은 record를 받아 저장·commit한다. 늦게 끝난 consumer의 처리는 no-op이고 commit은 거절된다 — 활동 2행, committed n+1.")
    @Test
    void slowConsumerLeavesAndAnotherProcessesTheSameRecord() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String topic = "w6-poll-interval-" + suffix;
        String group = "w6-poll-interval-group-" + suffix;
        KafkaTestSupport.ensureTopic(topic, 1);
        TopicPartition partition = new TopicPartition(topic, 0);
        t0 = System.currentTimeMillis();

        AtomicBoolean slowClaimed = new AtomicBoolean();
        AtomicReference<String> slowName = new AtomicReference<>();
        AtomicReference<Result> slowResult = new AtomicReference<>();
        AtomicReference<Result> fastResult = new AtomicReference<>();
        AtomicReference<Exception> slowCommitError = new AtomicReference<>();
        AtomicReference<Long> handOverAt = new AtomicReference<>();
        AtomicReference<Long> slowReceivedAt = new AtomicReference<>();
        List<String> deliveries = new CopyOnWriteArrayList<>();
        // 실험 topic에는 이 record 하나뿐이다. offset은 보낸 뒤에야 알 수 있어, 먼저 poll한 consumer가 건너뛰지 않게 내용으로 알아본다
        String json = orderConfirmedJson(UUID.randomUUID().toString(), 42L, AT, new long[][]{{7, 2}, {8, 1}});
        CountDownLatch done = new CountDownLatch(2);
        AtomicBoolean stop = new AtomicBoolean();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        for (String name : List.of("C-1", "C-2")) {
            pool.submit(() -> {
                try (KafkaConsumer<String, String> consumer = consumer(group)) {
                    consumer.subscribe(List.of(topic), new ConsumerRebalanceListener() {
                        @Override
                        public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
                            note(name, "revoked " + partitions);
                        }

                        @Override
                        public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
                            note(name, "assigned " + partitions);
                            if (partitions.contains(partition) && slowClaimed.get() && !name.equals(slowName.get())) {
                                handOverAt.compareAndSet(null, System.currentTimeMillis());
                            }
                        }

                        @Override
                        public void onPartitionsLost(Collection<TopicPartition> partitions) {
                            note(name, "lost " + partitions + " (이미 group에서 빠진 뒤라 revoke가 아니라 lost)");
                        }
                    });
                    boolean finished = false;
                    while (!stop.get() && !finished) {
                        for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(200))) {
                            if (!record.value().equals(json)) {
                                continue;
                            }
                            deliveries.add(name + "@" + record.offset());
                            if (slowClaimed.compareAndSet(false, true)) {
                                // 느린 쪽: 15초 동안 poll하지 않는다 (예: 느린 DB 쓰기)
                                slowName.set(name);
                                slowReceivedAt.set(System.currentTimeMillis());
                                note(name, "offset " + record.offset() + " 받음 → 처리 시작 (15초 걸림, 그동안 poll 안 함)");
                                Thread.sleep(SLOW_PROCESSING_MS);
                                Result result = purchaseActivityUseCase.consume(parser.parse(record), record.value());
                                slowResult.set(result);
                                note(name, "처리 끝 → handler " + result);
                                try {
                                    consumer.commitSync(Map.of(partition, new OffsetAndMetadata(record.offset() + 1)));
                                    note(name, "commit 성공 (예상과 다름)");
                                } catch (Exception e) {
                                    slowCommitError.set(e);
                                    note(name, "commit 거절 → " + e.getClass().getSimpleName());
                                }
                            } else {
                                // 다른 쪽: 넘겨받은 같은 record를 바로 처리
                                note(name, "같은 offset " + record.offset() + " 받음 (committed offset부터 다시 읽음)");
                                Result result = purchaseActivityUseCase.consume(parser.parse(record), record.value());
                                fastResult.set(result);
                                consumer.commitSync(Map.of(partition, new OffsetAndMetadata(record.offset() + 1)));
                                note(name, "handler " + result + " → commit 성공 (committed " + (record.offset() + 1) + ")");
                            }
                            finished = true;
                        }
                    }
                } catch (Exception e) {
                    note(name, "예외 " + e);
                } finally {
                    done.countDown();
                }
                return null;
            });
        }

        // 두 consumer가 group에 들어와 하나가 partition을 받을 때까지 기다린 뒤 보낸다
        await().atMost(Duration.ofSeconds(30)).until(() -> KafkaTestSupport.admin().describeConsumerGroups(List.of(group))
            .all().get().get(group).members().size() == 2
            && KafkaTestSupport.admin().describeConsumerGroups(List.of(group)).all().get().get(group).state().name().equals("STABLE"));
        RecordMetadata sent = kafkaTemplate.send(new ProducerRecord<>(topic, "42", json)).get(10, TimeUnit.SECONDS).getRecordMetadata();
        note("test", "record 보냄 → offset " + sent.offset() + " (max.poll.interval.ms=" + MAX_POLL_INTERVAL_MS + ")");

        boolean bothDone = done.await(60, TimeUnit.SECONDS);
        stop.set(true);
        pool.shutdownNow();

        assertThat(handOverAt.get()).as("다른 consumer가 partition을 넘겨받음").isNotNull();
        double gap = (handOverAt.get() - slowReceivedAt.get()) / 1000.0;
        note("test", String.format("느린 consumer가 받은 뒤 다른 consumer가 partition을 받기까지 %.1fs", gap));
        System.out.println("[E-8] 요약 | 전달=" + deliveries + " | 느린 쪽 handler=" + slowResult.get() + " commit=" + slowCommitError.get()
            + " | 빠른 쪽 handler=" + fastResult.get() + " | committed=" + KafkaTestSupport.committedOffset(group, partition)
            + " | 활동 행=" + activityCount());

        assertThat(bothDone).isTrue();
        assertThat(deliveries).hasSize(2);
        assertThat(gap).isBetween(MAX_POLL_INTERVAL_MS / 1000.0 - 1, SLOW_PROCESSING_MS / 1000.0);
        assertThat(fastResult.get()).isEqualTo(Result.SAVED);
        assertThat(slowResult.get()).isEqualTo(Result.DUPLICATE);
        assertThat(slowCommitError.get()).isInstanceOf(CommitFailedException.class);
        assertThat(KafkaTestSupport.committedOffset(group, partition)).isEqualTo(sent.offset() + 1);
        assertThat(activityCount()).isEqualTo(2);
        assertThat(consumedCount()).isEqualTo(1);
    }

    private KafkaConsumer<String, String> consumer(String group) {
        return new KafkaConsumer<>(Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestContainersConfig.bootstrapServers(),
            ConsumerConfig.GROUP_ID_CONFIG, group,
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
            ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, MAX_POLL_INTERVAL_MS));
    }
}
