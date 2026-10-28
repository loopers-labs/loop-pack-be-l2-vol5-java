package com.loopers.kafka;

import com.loopers.confg.kafka.KafkaConfig;
import com.loopers.purchase.adapter.in.kafka.OrderConfirmedMessageParser;
import com.loopers.purchase.application.port.in.PurchaseActivityUseCase;
import com.loopers.purchase.application.port.in.PurchaseActivityUseCase.Result;
import com.loopers.support.KafkaTestSupport;
import com.loopers.support.StreamerIntegrationTestSupport;
import com.loopers.testcontainers.KafkaTestContainersConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.loopers.support.KafkaTestSupport.orderConfirmedJson;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * E-2 (W6 plan.md D-8, C-5) — DB commit 뒤 offset을 commit하지 않으면 같은 record가 다시 온다. 멱등 handler가 효과를 한 번으로 지킨다.
 * <p>
 * low-level KafkaConsumer로 첫 실행의 offset commit만 보류한다. 파싱·업무 handler는 애플리케이션 listener와 같은 것을 쓴다.
 * 같은 broker·topic·group·DB를 유지하고 consumer만 새로 만든다. 두 번째 consumer는 seek·offset reset 없이 저장된 group offset에서 읽는다.
 * 업무 topic·group과 섞이지 않게 실험 전용 topic(partition 1)과 group을 쓴다.
 */
class RedeliveryAfterDbCommitTest extends StreamerIntegrationTestSupport {

    private static final String AT = "2026-10-28T14:03:12.123456+09:00";

    @Autowired
    @Qualifier(KafkaConfig.STRING_TEMPLATE)
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private OrderConfirmedMessageParser parser;

    @Autowired
    private PurchaseActivityUseCase purchaseActivityUseCase;

    @DisplayName("C-5 E-2 첫 consumer가 DB에 저장한 뒤 offset을 commit하지 않고 닫으면, 같은 group의 새 consumer가 같은 topic·partition·offset n을 다시 받고 no-op 뒤 n+1을 commit한다 — committed n → n → n+1, 활동 2행 불변.")
    @Test
    void sameRecordIsRedeliveredAfterDbCommitWithoutOffsetCommit() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String topic = "w6-redelivery-" + suffix;
        String group = "w6-redelivery-group-" + suffix;
        KafkaTestSupport.ensureTopic(topic, 1);
        String json = orderConfirmedJson(UUID.randomUUID().toString(), 42L, AT, new long[][]{{7, 2}, {8, 1}});

        // 준비: target 발행 → broker가 정한 위치 n
        RecordMetadata target = kafkaTemplate.send(new ProducerRecord<>(topic, "42", json)).get(10, TimeUnit.SECONDS).getRecordMetadata();
        TopicPartition partition = new TopicPartition(topic, target.partition());
        long n = target.offset();
        List<Long> committedHistory = new ArrayList<>();
        List<String> deliveries = new ArrayList<>();

        // 첫 consumer: 구독·배정 → 다음 읽을 위치 n을 commit하고 n에서 읽기 → handler DB commit → n+1은 commit하지 않음
        try (KafkaConsumer<String, String> first = consumer(group, "earliest")) {
            first.subscribe(List.of(topic));
            awaitAssignment(first, partition); // 배정을 기다리는 poll이 target을 미리 가져와도 처리하지 않고 아래에서 n으로 되돌린다
            first.commitSync(Map.of(partition, new OffsetAndMetadata(n)));
            first.seek(partition, n);
            committedHistory.add(KafkaTestSupport.committedOffset(group, partition));

            ConsumerRecord<String, String> record = pollTarget(first, n);
            deliveries.add(record.topic() + "-" + record.partition() + "@" + record.offset());
            assertThat(purchaseActivityUseCase.consume(parser.parse(record), record.value())).isEqualTo(Result.SAVED);
            assertThat(activityCount()).isEqualTo(2);
            assertThat(consumedCount()).isEqualTo(1);

            // 종료 전: n+1을 요청하지 않는다. auto commit false라 close가 대신 commit하지 않는다
            committedHistory.add(KafkaTestSupport.committedOffset(group, partition));
        } // bounded close (기본 30s 상한), 대기 중인 commit 없음

        // 재시작: 같은 group의 새 consumer. seek·offset reset 없이 저장된 group offset(n)에서 읽는다
        try (KafkaConsumer<String, String> second = consumer(group, "none")) {
            second.subscribe(List.of(topic));
            ConsumerRecord<String, String> again = pollTarget(second, n);
            deliveries.add(again.topic() + "-" + again.partition() + "@" + again.offset());

            assertThat(purchaseActivityUseCase.consume(parser.parse(again), again.value())).isEqualTo(Result.DUPLICATE);
            second.commitSync(Map.of(partition, new OffsetAndMetadata(again.offset() + 1)));
            committedHistory.add(KafkaTestSupport.committedOffset(group, partition));
        }

        // 관찰값: 전달 2회 · 같은 위치 · 활동 행 수 불변 · committed n → n → n+1
        assertThat(deliveries).containsExactly(topic + "-0@" + n, topic + "-0@" + n);
        assertThat(committedHistory).containsExactly(n, n, n + 1);
        assertThat(activityCount()).isEqualTo(2);
        assertThat(consumedCount()).isEqualTo(1);
        System.out.printf("[E-2] topic=%s group=%s | 전달=%s | committed=%s | 활동 행=%d%n",
            topic, group, deliveries, committedHistory, activityCount());
    }

    /**
     * 첫 consumer는 아직 group offset이 없어 배정 직후 위치를 정할 기준(earliest)이 필요하다 — 곧바로 n을 commit하고 n으로 seek한다.
     * 두 번째 consumer는 none: 저장된 group offset이 없으면 실패하므로, 정말 저장된 위치에서 읽는지 보장한다.
     */
    private KafkaConsumer<String, String> consumer(String group, String autoOffsetReset) {
        return new KafkaConsumer<>(Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestContainersConfig.bootstrapServers(),
            ConsumerConfig.GROUP_ID_CONFIG, group,
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, autoOffsetReset));
    }

    /** 배정은 poll 안에서 일어난다. 잠만 자지 않고 poll하며 기다린다. */
    private static void awaitAssignment(KafkaConsumer<String, String> consumer, TopicPartition partition) {
        long deadline = System.currentTimeMillis() + 30_000;
        while (!consumer.assignment().contains(partition)) {
            if (System.currentTimeMillis() > deadline) {
                throw new IllegalStateException("배정되지 않았습니다: " + partition);
            }
            consumer.poll(Duration.ofMillis(200));
        }
    }

    private static ConsumerRecord<String, String> pollTarget(KafkaConsumer<String, String> consumer, long offset) {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
            for (ConsumerRecord<String, String> record : records) {
                if (record.offset() == offset) {
                    return record;
                }
            }
        }
        throw new IllegalStateException("offset " + offset + " record를 받지 못했습니다");
    }
}
