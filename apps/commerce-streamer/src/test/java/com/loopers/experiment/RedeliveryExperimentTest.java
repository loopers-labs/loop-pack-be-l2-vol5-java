package com.loopers.experiment;

import com.loopers.application.purchase.PurchaseActivityFacade;
import com.loopers.domain.purchase.ProductPurchaseActivity;
import com.loopers.domain.purchase.ProductPurchaseActivityRepository;
import com.loopers.domain.purchase.RecordAddress;
import com.loopers.support.KafkaProbe;
import com.loopers.testcontainers.KafkaTestContainers;
import com.loopers.utils.DatabaseCleanUp;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.loopers.support.KafkaProbe.item;
import static com.loopers.support.KafkaProbe.json;
import static com.loopers.support.KafkaProbe.nextId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * 실험 B. 저수준 재전달 — DB commit 뒤 offset을 commit하지 못하고 consumer가 사라지면, 같은 group의 다음 consumer가 같은 기록을
 * 다시 받는다. 효과는 한 번만 남는다(docs/week6/design.md 9번, docs/week6/decisions.md D-21 B, D-11, INV-605).
 *
 * <p>Spring listener 대신 저수준 {@link KafkaConsumer}(enable.auto.commit=false)를 직접 몰고, handler는 실제 빈
 * ({@link PurchaseActivityFacade} — 안에서 실제 {@code OrderConfirmedMessageReader}로 파싱한다)을 그대로 부른다.
 * 앱의 @KafkaListener는 이 클래스만의 고유 topic을 듣게 하고, 실험 consumer는 그와 다른 고유 topic을 쓴다 — listener가
 * 실험 기록을 먼저 처리해 "두 번째 전달이 no-op"을 흐리지 않게 한다.
 *
 * <p>committed offset은 position이나 로그가 아니라 admin으로 group의 값을 읽는다. commit 값은 "다음에 읽을 위치"다 —
 * offset n을 처리했으면 n+1을 commit한다. Spring {@code MANUAL_IMMEDIATE}의 {@code ack.acknowledge()}도 consumer position이
 * 아니라 그 기록의 {@code offset() + 1}을 지정해 commit한다(spring-kafka 3.3.4 {@code buildSingleCommits}). 이 실험이
 * D-11의 근거를 저수준에서 보인다.
 *
 * <p>추가 장면(과제 밖)은 인자 없는 {@code commitSync()}가 처리한 곳이 아니라 poll이 옮겨 놓은 position을 commit해
 * 처리하지 않은 기록을 건너뛰게 만드는 것을 보인다. 구매 활동 handler 없이 실험 topic에서만 한다.
 * 추가 장면 2(과제 밖)는 그 position이 broker에 쌓인 만큼이 아니라 poll이 돌려준 만큼만 움직이는 것을 보인다
 * (kafka-clients 3.8.1 {@code FetchCollector#fetchRecords}).
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedeliveryExperimentTest {

    private static final String LISTENER_TOPIC = KafkaProbe.unique("order-events-redelivery-listener");
    private static final String LISTENER_GROUP = KafkaProbe.unique("purchase-activity-redelivery-listener");
    private static final Duration WAIT = Duration.ofSeconds(30);
    private static final Duration POLL = Duration.ofMillis(100);
    private static final Duration CLOSE = Duration.ofSeconds(5);

    @DynamicPropertySource
    static void topic(DynamicPropertyRegistry registry) {
        KafkaTestContainers.createTopic(LISTENER_TOPIC);
        registry.add("events.order-confirmed.topic", () -> LISTENER_TOPIC);
        registry.add("events.order-confirmed.group-id", () -> LISTENER_GROUP);
    }

    @Autowired
    private PurchaseActivityFacade handler;

    @Autowired
    private ProductPurchaseActivityRepository activityRepository;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterAll
    void cleanUp() {
        databaseCleanUp.truncateAllTables();
    }

    private static KafkaConsumer<String, byte[]> consumer(String topic, String group) {
        return consumer(topic, group, Map.of());
    }

    private static KafkaConsumer<String, byte[]> consumer(String topic, String group, Map<String, Object> extra) {
        Map<String, Object> config = new HashMap<>(Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestContainers.bootstrapServers(),
            ConsumerConfig.GROUP_ID_CONFIG, group,
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class,
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"));
        config.putAll(extra);
        KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(config);
        consumer.subscribe(List.of(topic));
        return consumer;
    }

    /** 배정에 tp가 들어올 때까지 poll한다. 그 사이 받은 기록은 버리지 않고 모은다. */
    private static List<ConsumerRecord<String, byte[]>> awaitAssigned(KafkaConsumer<String, byte[]> consumer, TopicPartition tp) {
        List<ConsumerRecord<String, byte[]>> fetched = new ArrayList<>();
        await().atMost(WAIT).pollInSameThread().pollDelay(Duration.ZERO).pollInterval(Duration.ofMillis(1))
            .until(() -> {
                consumer.poll(POLL).forEach(fetched::add);
                return consumer.assignment().contains(tp);
            });
        return fetched;
    }

    /** tp에서 offset이 from 이상인 기록을 count개 모을 때까지 poll한다. */
    private static List<ConsumerRecord<String, byte[]>> pollUntil(KafkaConsumer<String, byte[]> consumer, TopicPartition tp,
                                                                  long from, int count, List<ConsumerRecord<String, byte[]>> fetched) {
        await().atMost(WAIT).pollInSameThread().pollDelay(Duration.ZERO).pollInterval(Duration.ofMillis(1))
            .until(() -> {
                consumer.poll(POLL).forEach(fetched::add);
                return of(fetched, tp, from).size() >= count;
            });
        return of(fetched, tp, from);
    }

    private static List<ConsumerRecord<String, byte[]>> of(List<ConsumerRecord<String, byte[]>> fetched, TopicPartition tp, long from) {
        return fetched.stream()
            .filter(r -> r.topic().equals(tp.topic()) && r.partition() == tp.partition() && r.offset() >= from)
            .toList();
    }

    private void handle(ConsumerRecord<String, byte[]> record) {
        handler.handle(new RecordAddress(record.topic(), record.partition(), record.offset()), record.key(), record.value());
    }

    private int processedCount(long orderId) {
        Integer count = new JdbcTemplate(dataSource).queryForObject(
            "SELECT COUNT(*) FROM processed_order_confirmation WHERE order_id = ?", Integer.class, orderId);
        return count == null ? 0 : count;
    }

    private static String address(ConsumerRecord<?, ?> record) {
        return record.topic() + "-" + record.partition() + "@" + record.offset();
    }

    @DisplayName("B: DB commit 뒤 offset을 commit하지 않고 닫으면, 같은 group의 새 consumer가 seek 없이 같은 기록을 다시 받는다. "
        + "두 번째 처리는 no-op이고 committed offset은 n → n → n+1이다.")
    @Test
    void uncommittedRecordIsRedeliveredAndAppliedOnce() {
        String topic = KafkaProbe.unique("experiment-redelivery");
        String group = KafkaProbe.unique("experiment-redelivery-group");
        KafkaTestContainers.createTopic(topic);
        long orderId = nextId();
        long productA = nextId();
        long productB = nextId();

        try (KafkaProbe kafka = new KafkaProbe(topic, group)) {
            // arrange — 계약 모양의 OrderConfirmed 1건. send metadata로 partition과 offset n을 확보한다
            String value = json(orderId, ZonedDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS),
                item(productA, 2), item(productB, 3));
            RecordMetadata sent = kafka.send(String.valueOf(orderId), value);
            TopicPartition tp = new TopicPartition(topic, sent.partition());
            long n = sent.offset();

            // act 1 — 첫 consumer: 배정된 뒤 n을 commit하고, n을 읽어 handler가 DB commit한 뒤, n+1을 commit하지 않고 닫는다
            ConsumerRecord<String, byte[]> first;
            long committedAtStart;
            try (KafkaConsumer<String, byte[]> consumer1 = consumer(topic, group)) {
                List<ConsumerRecord<String, byte[]>> fetched = awaitAssigned(consumer1, tp);
                consumer1.commitSync(Map.of(tp, new OffsetAndMetadata(n)));
                committedAtStart = kafka.committed(tp.partition());
                first = pollUntil(consumer1, tp, n, 1, fetched).get(0);
                handle(first);
                consumer1.close(CLOSE); // auto commit 없음, 종료 commit 없음
            }
            long committedAfterClose = kafka.committed(tp.partition());
            List<ProductPurchaseActivity> afterFirst = activityRepository.findAllByOrderId(orderId);

            // act 2 — 같은 group의 새 consumer: seek 없이 같은 기록을 받는다 → handler no-op → n+1을 명시해 commit
            ConsumerRecord<String, byte[]> second;
            try (KafkaConsumer<String, byte[]> consumer2 = consumer(topic, group)) {
                List<ConsumerRecord<String, byte[]>> fetched = awaitAssigned(consumer2, tp);
                second = pollUntil(consumer2, tp, n, 1, fetched).get(0);
                handle(second);
                consumer2.commitSync(Map.of(tp, new OffsetAndMetadata(second.offset() + 1)));
                consumer2.close(CLOSE);
            }
            long committedAfterSecond = kafka.committed(tp.partition());
            List<ProductPurchaseActivity> afterSecond = activityRepository.findAllByOrderId(orderId);

            // assert
            System.out.printf("[B] send → %s-%d@%d (n=%d)%n", topic, sent.partition(), n, n);
            System.out.printf("[B] consumer1: 배정 뒤 commit(n) → committed=%d, 받은 기록=%s, handler DB commit, "
                + "n+1 commit 없이 close → committed=%d%n", committedAtStart, address(first), committedAfterClose);
            System.out.printf("[B] consumer2: seek 없이 받은 기록=%s, handler no-op, commit(n+1) → committed=%d%n",
                address(second), committedAfterSecond);
            System.out.printf("[B] 전달 2회, committed %d → %d → %d, 구매 활동 행 %d → %d, 처리 기록 %d건%n",
                committedAtStart, committedAfterClose, committedAfterSecond, afterFirst.size(), afterSecond.size(),
                processedCount(orderId));
            assertAll(
                () -> assertThat(address(first)).isEqualTo(address(second)),
                () -> assertThat(first.offset()).isEqualTo(n),
                () -> assertThat(committedAtStart).isEqualTo(n),
                () -> assertThat(committedAfterClose).isEqualTo(n),
                () -> assertThat(committedAfterSecond).isEqualTo(n + 1),
                () -> assertThat(afterFirst).extracting(ProductPurchaseActivity::getProductId, ProductPurchaseActivity::getQuantity)
                    .containsExactlyInAnyOrder(tuple(productA, 2),
                        tuple(productB, 3)),
                () -> assertThat(afterSecond).extracting(ProductPurchaseActivity::getProductId, ProductPurchaseActivity::getQuantity)
                    .containsExactlyInAnyOrderElementsOf(
                        afterFirst.stream().map(a -> tuple(a.getProductId(), a.getQuantity())).toList()),
                () -> assertThat(processedCount(orderId)).isEqualTo(1)
            );
        }
    }

    @DisplayName("B 추가(과제 밖): 5건을 받고 첫 건만 처리한 뒤 인자 없이 commitSync()하면 position(m+5)이 commit돼, "
        + "같은 group의 새 consumer는 m+5부터 받는다 — m+1~m+4는 처리되지 않은 채 건너뛴다.")
    @Test
    void noArgCommitSkipsUnprocessedRecords() {
        String topic = KafkaProbe.unique("experiment-redelivery-extra");
        String group = KafkaProbe.unique("experiment-redelivery-extra-group");
        KafkaTestContainers.createTopic(topic);
        int partition = 0;
        TopicPartition tp = new TopicPartition(topic, partition);

        try (KafkaProbe kafka = new KafkaProbe(topic, group)) {
            // arrange — 한 partition에 5건(m ~ m+4)
            List<RecordMetadata> sent = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                sent.add(kafka.send(partition, "extra-" + i, ("extra-" + i).getBytes(StandardCharsets.UTF_8)));
            }
            long m = sent.get(0).offset();

            // act 1 — 5건을 다 받을 때까지 poll하고, m만 "처리"한 뒤 인자 없는 commitSync()
            List<ConsumerRecord<String, byte[]>> fetched;
            long position;
            try (KafkaConsumer<String, byte[]> consumer1 = consumer(topic, group)) {
                List<ConsumerRecord<String, byte[]>> buffer = awaitAssigned(consumer1, tp);
                fetched = pollUntil(consumer1, tp, m, 5, buffer);
                List<ConsumerRecord<String, byte[]>> processed = List.of(fetched.get(0)); // m만 처리했다
                position = consumer1.position(tp);
                consumer1.commitSync();
                consumer1.close(CLOSE);
                System.out.printf("[B-extra] 받은 기록 %s, 처리한 기록 %s, position=%d%n",
                    fetched.stream().map(r -> String.valueOf(r.offset())).toList(),
                    processed.stream().map(r -> String.valueOf(r.offset())).toList(), position);
            }
            long committed = kafka.committed(partition);

            // act 2 — 뒤에 한 건을 더 보내고, 같은 group의 새 consumer가 어디서부터 받는지 본다
            RecordMetadata next = kafka.send(partition, "extra-5", "extra-5".getBytes(StandardCharsets.UTF_8));
            long newPosition;
            ConsumerRecord<String, byte[]> firstFetched;
            List<ConsumerRecord<String, byte[]>> newFetched;
            try (KafkaConsumer<String, byte[]> consumer2 = consumer(topic, group)) {
                List<ConsumerRecord<String, byte[]>> buffer = awaitAssigned(consumer2, tp);
                newFetched = pollUntil(consumer2, tp, m, 1, buffer);
                firstFetched = newFetched.get(0);
                newPosition = consumer2.position(tp);
                consumer2.close(CLOSE);
            }

            // assert
            System.out.printf("[B-extra] m=%d, commitSync() 뒤 committed=%d (=m+5), 새 consumer의 첫 기록 offset=%d, "
                    + "position=%d → m+1~m+4(%d~%d)는 처리되지 않은 채 건너뜀%n",
                m, committed, firstFetched.offset(), newPosition, m + 1, m + 4);
            assertAll(
                () -> assertThat(fetched).extracting(ConsumerRecord::offset).containsExactly(m, m + 1, m + 2, m + 3, m + 4),
                () -> assertThat(position).isEqualTo(m + 5),
                () -> assertThat(committed).isEqualTo(m + 5),
                () -> assertThat(next.offset()).isEqualTo(m + 5),
                () -> assertThat(firstFetched.offset()).isEqualTo(m + 5),
                () -> assertThat(newFetched).extracting(ConsumerRecord::offset).doesNotContain(m + 1, m + 2, m + 3, m + 4)
            );
        }
    }

    @DisplayName("B 추가 2(과제 밖): max.poll.records=2로 5건이 쌓인 partition을 poll하면 한 번에 2건만 돌려주고, "
        + "position과 인자 없는 commitSync()의 값은 돌려준 만큼(m+2)이다 — 5건 전부가 아니다.")
    @Test
    void positionFollowsReturnedRecordsNotAvailable() {
        String topic = KafkaProbe.unique("experiment-redelivery-returned");
        String group = KafkaProbe.unique("experiment-redelivery-returned-group");
        KafkaTestContainers.createTopic(topic);
        int partition = 0;
        TopicPartition tp = new TopicPartition(topic, partition);

        try (KafkaProbe kafka = new KafkaProbe(topic, group)) {
            // arrange — 한 partition에 5건(m ~ m+4)을 먼저 쌓아 둔다
            List<RecordMetadata> sent = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                sent.add(kafka.send(partition, "returned-" + i, ("returned-" + i).getBytes(StandardCharsets.UTF_8)));
            }
            long m = sent.get(0).offset();

            // act — 기록이 처음 돌아온 poll 한 번만 보고, 곧바로 position을 읽고 인자 없이 commit한다
            List<ConsumerRecord<String, byte[]>> firstBatch = new ArrayList<>();
            List<ConsumerRecord<String, byte[]>> rest;
            long position;
            try (KafkaConsumer<String, byte[]> consumer1 = consumer(topic, group,
                Map.of(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 2))) {
                await().atMost(WAIT).pollInSameThread().pollDelay(Duration.ZERO).pollInterval(Duration.ofMillis(1))
                    .until(() -> {
                        consumer1.poll(POLL).forEach(firstBatch::add);
                        return !firstBatch.isEmpty();
                    });
                position = consumer1.position(tp);
                consumer1.commitSync();
                rest = pollUntil(consumer1, tp, m + 2, 3, new ArrayList<>());
                consumer1.close(CLOSE);
            }
            long committed = kafka.committed(partition);

            // assert
            System.out.printf("[B-extra2] 쌓인 기록 m=%d ~ m+4=%d, max.poll.records=2%n", m, m + 4);
            System.out.printf("[B-extra2] 첫 poll이 돌려준 기록 %s, position=%d, commitSync() 뒤 committed=%d (=m+2), "
                    + "이어서 받은 기록 %s%n",
                firstBatch.stream().map(r -> String.valueOf(r.offset())).toList(), position, committed,
                rest.stream().map(r -> String.valueOf(r.offset())).toList());
            assertAll(
                () -> assertThat(firstBatch).extracting(ConsumerRecord::offset).containsExactly(m, m + 1),
                () -> assertThat(position).isEqualTo(m + 2),
                () -> assertThat(committed).isEqualTo(m + 2),
                () -> assertThat(rest).extracting(ConsumerRecord::offset).containsExactly(m + 2, m + 3, m + 4)
            );
        }
    }
}
