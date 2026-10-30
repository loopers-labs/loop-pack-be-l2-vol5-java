package com.loopers.kafka;

import com.loopers.testcontainers.KafkaTestContainersConfig;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import static com.loopers.kafka.KafkaLearningSupport.consumer;
import static com.loopers.kafka.KafkaLearningSupport.consumerConfig;
import static com.loopers.kafka.KafkaLearningSupport.producer;
import static com.loopers.kafka.test.KafkaTestSupport.POLL_TIMEOUT;
import static com.loopers.kafka.test.KafkaTestSupport.admin;
import static com.loopers.kafka.test.KafkaTestSupport.awaitUntil;
import static com.loopers.kafka.test.KafkaTestSupport.closeAll;
import static com.loopers.kafka.test.KafkaTestSupport.committedOffset;
import static com.loopers.kafka.test.KafkaTestSupport.createTopic;
import static com.loopers.kafka.test.KafkaTestSupport.uniqueName;
import static org.assertj.core.api.Assertions.assertThat;

@SpringJUnitConfig(KafkaTestContainersConfig.class)
class KafkaRedeliveryLearningTest {

    // committed offset이 없으면 예외가 나도록 해, 재전달이 offset reset이 아니라 committed offset에서 시작했음을 보장한다.
    private static final String NO_OFFSET_RESET = "none";

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @DisplayName("record n을 읽고 n+1을 commit하지 않은 채 종료하면 같은 group의 새 consumer가 seek 없이 같은 offset n을 다시 받는다")
    @Test
    void uncommittedRecordIsRedeliveredToNextConsumer() throws Exception {
        String topic = uniqueName("learning-redelivery");
        String groupId = uniqueName("learning-redelivery-group");
        TopicPartition partition = new TopicPartition(topic, 0);
        List<Long> committedHistory = new ArrayList<Long>();
        List<AutoCloseable> closeables = new ArrayList<AutoCloseable>();
        Admin admin = admin(bootstrapServers);
        closeables.add(admin);
        try {
            createTopic(admin, topic, 1);
            KafkaProducer<String, String> producer = producer(bootstrapServers);
            closeables.add(producer);
            long n = producer.send(new ProducerRecord<>(topic, "1001", "order-confirmed")).get(10, TimeUnit.SECONDS).offset();

            Map<String, Object> firstConfig = consumerConfig(bootstrapServers, groupId, "first-consumer", NO_OFFSET_RESET);
            assertThat(firstConfig.get(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG)).isEqualTo(false);
            KafkaConsumer<String, String> first = consumer(firstConfig);
            closeables.add(first);
            first.subscribe(List.of(topic), commitOnAssign(first, partition, n));
            ConsumerRecord<String, String> firstRecord = pollOne(first, "첫 consumer의 offset n 수신");
            committedHistory.add(committedOffset(admin, groupId, partition));
            first.close();
            closeables.remove(first);
            committedHistory.add(committedOffset(admin, groupId, partition));

            KafkaConsumer<String, String> second = consumer(
                consumerConfig(bootstrapServers, groupId, "second-consumer", NO_OFFSET_RESET));
            closeables.add(second);
            second.subscribe(List.of(topic));
            ConsumerRecord<String, String> redelivered = pollOne(second, "새 consumer의 offset n 재수신");
            second.commitSync(Map.of(partition, new OffsetAndMetadata(n + 1)));
            committedHistory.add(committedOffset(admin, groupId, partition));

            System.out.println("[재전달] n=" + n + ", committed 변화=" + committedHistory);
            System.out.println("  첫 수신 (" + firstRecord.topic() + ", " + firstRecord.partition() + ", " + firstRecord.offset() + ")");
            System.out.println("  재수신 (" + redelivered.topic() + ", " + redelivered.partition() + ", " + redelivered.offset() + ")");
            assertThat(firstRecord.offset()).isEqualTo(n);
            assertThat(redelivered.topic()).isEqualTo(firstRecord.topic());
            assertThat(redelivered.partition()).isEqualTo(firstRecord.partition());
            assertThat(redelivered.offset()).isEqualTo(n);
            assertThat(committedHistory).containsExactly(n, n, n + 1);
        } finally {
            closeAll(closeables.reversed());
        }
    }

    @DisplayName("commit 없이 poll을 두 번 하면 두 번째 poll은 다음 record를 받고 committed offset은 그대로다")
    @Test
    void positionMovesWhileCommittedStays() throws Exception {
        String topic = uniqueName("learning-position");
        String groupId = uniqueName("learning-position-group");
        TopicPartition partition = new TopicPartition(topic, 0);
        Admin admin = admin(bootstrapServers);
        KafkaProducer<String, String> producer = producer(bootstrapServers);
        KafkaConsumer<String, String> consumer = consumer(
            consumerConfig(bootstrapServers, groupId, "position-consumer", NO_OFFSET_RESET));
        try {
            createTopic(admin, topic, 1);
            long start = producer.send(new ProducerRecord<>(topic, "1001", "first")).get(10, TimeUnit.SECONDS).offset();
            producer.send(new ProducerRecord<>(topic, "1001", "second")).get(10, TimeUnit.SECONDS);
            consumer.subscribe(List.of(topic), commitOnAssign(consumer, partition, start));

            ConsumerRecord<String, String> firstPolled = pollOne(consumer, "첫 poll의 record 수신");
            long positionAfterFirst = consumer.position(partition);
            Long committedAfterFirst = committedOffset(admin, groupId, partition);
            ConsumerRecord<String, String> secondPolled = pollOne(consumer, "두 번째 poll의 record 수신");
            long positionAfterSecond = consumer.position(partition);
            Long committedAfterSecond = committedOffset(admin, groupId, partition);

            System.out.println("[position·committed] 첫 poll offset=" + firstPolled.offset() + " position=" + positionAfterFirst
                + " committed=" + committedAfterFirst + " / 두 번째 poll offset=" + secondPolled.offset()
                + " position=" + positionAfterSecond + " committed=" + committedAfterSecond);
            assertThat(firstPolled.offset()).isEqualTo(start);
            assertThat(secondPolled.offset()).isEqualTo(start + 1);
            assertThat(positionAfterFirst).isEqualTo(start + 1);
            assertThat(positionAfterSecond).isEqualTo(start + 2);
            assertThat(committedAfterFirst).isEqualTo(start);
            assertThat(committedAfterSecond).isEqualTo(start);
        } finally {
            closeAll(List.of(consumer, producer, admin));
        }
    }

    // 배정 직후 fetch 전에 시작 위치를 commit해 둔다. 이후 이 테스트는 위치를 직접 옮기지 않는다.
    private ConsumerRebalanceListener commitOnAssign(
        KafkaConsumer<String, String> consumer,
        TopicPartition partition,
        long startOffset
    ) {
        return new ConsumerRebalanceListener() {
            @Override
            public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
            }

            @Override
            public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
                if (partitions.contains(partition)) {
                    consumer.commitSync(Map.of(partition, new OffsetAndMetadata(startOffset)));
                }
            }
        };
    }

    private ConsumerRecord<String, String> pollOne(KafkaConsumer<String, String> consumer, String description) {
        List<ConsumerRecord<String, String>> polled = new ArrayList<ConsumerRecord<String, String>>();
        awaitUntil(description, () -> {
            consumer.poll(POLL_TIMEOUT).forEach(polled::add);
            return !polled.isEmpty();
        });
        assertThat(polled).hasSize(1);
        return polled.getFirst();
    }
}
