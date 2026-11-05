package com.loopers.kafka;

import com.loopers.application.purchase.ConsumeResult;
import com.loopers.application.purchase.EventSource;
import com.loopers.application.purchase.PurchaseActivityHandler;
import com.loopers.interfaces.consumer.OrderConfirmedEventParser;
import com.loopers.interfaces.consumer.OrderEventParseResult;
import com.loopers.support.PurchaseActivityTables;
import com.loopers.utils.DatabaseCleanUp;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

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
import static com.loopers.support.OrderConfirmedMessages.json;
import static org.assertj.core.api.Assertions.assertThat;

// 과제 실험 ②: DB commit은 끝났지만 offset을 commit하지 못한 record가 재전달돼도 실제 파서·handler가 구매 활동을 한 번만 남기는지 본다.
// Spring listener 대신 low-level KafkaConsumer로 offset commit 시점을 직접 고른다.
@SpringBootTest
class PurchaseActivityRedeliveryTest {

    // 이 context의 order-events listener가 실험 topic을 읽지 않도록 다른 topic을 준다.
    private static final String LISTENER_TOPIC = "order-events-redelivery-listener-" + UUID.randomUUID();
    private static final String NO_OFFSET_RESET = "none";

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Autowired
    private OrderConfirmedEventParser orderConfirmedEventParser;

    @Autowired
    private PurchaseActivityHandler purchaseActivityHandler;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @DynamicPropertySource
    static void orderEventsTopic(DynamicPropertyRegistry registry) {
        registry.add("commerce.kafka.topics.order-events", () -> LISTENER_TOPIC);
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("구매 활동을 DB에 commit한 뒤 offset n+1을 commit하지 못하고 종료하면 같은 group의 새 consumer가 offset n을 다시 받고, handler는 아무것도 하지 않아 구매 활동이 그대로다")
    @Test
    void redeliveryAfterDbCommitIsNoOp() throws Exception {
        String topic = uniqueName("order-events-redelivery");
        String groupId = uniqueName("purchase-activity-redelivery");
        TopicPartition partition = new TopicPartition(topic, 0);
        PurchaseActivityTables tables = new PurchaseActivityTables(jdbcTemplate);
        List<Long> committedHistory = new ArrayList<Long>();
        List<ConsumeResult> results = new ArrayList<ConsumeResult>();
        List<AutoCloseable> closeables = new ArrayList<AutoCloseable>();
        Admin admin = admin(bootstrapServers);
        closeables.add(admin);
        try {
            createTopic(admin, topic, 1);
            KafkaProducer<String, String> producer = producer(bootstrapServers);
            closeables.add(producer);
            long n = producer.send(new ProducerRecord<>(topic, "42", json("evt-42", 42, 7, 2, 8, 1)))
                .get(10, TimeUnit.SECONDS)
                .offset();

            Map<String, Object> firstConfig = consumerConfig(bootstrapServers, groupId, "first-consumer", NO_OFFSET_RESET);
            assertThat(firstConfig.get(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG)).isEqualTo(false);
            KafkaConsumer<String, String> first = consumer(firstConfig);
            closeables.add(first);
            first.subscribe(List.of(topic), commitOnAssign(first, partition, n));
            ConsumerRecord<String, String> firstDelivery = pollOne(first, "첫 consumer의 offset n 수신");
            results.add(handle(firstDelivery));
            long activitiesAfterFirst = tables.activities();
            long quantityOf7AfterFirst = tables.quantityOf(7);
            committedHistory.add(committedOffset(admin, groupId, partition));
            first.close();
            closeables.remove(first);
            committedHistory.add(committedOffset(admin, groupId, partition));

            KafkaConsumer<String, String> second = consumer(
                consumerConfig(bootstrapServers, groupId, "second-consumer", NO_OFFSET_RESET));
            closeables.add(second);
            second.subscribe(List.of(topic));
            ConsumerRecord<String, String> secondDelivery = pollOne(second, "새 consumer의 offset n 재수신");
            results.add(handle(secondDelivery));
            second.commitSync(Map.of(partition, new OffsetAndMetadata(n + 1)));
            committedHistory.add(committedOffset(admin, groupId, partition));

            System.out.println("[실험②] n=" + n + ", 전달 2회 위치=" + position(firstDelivery) + " / " + position(secondDelivery)
                + ", handler 결과=" + results + ", 구매 활동 " + activitiesAfterFirst + "행 → " + tables.activities()
                + "행, 상품 7 수량 " + quantityOf7AfterFirst + " → " + tables.quantityOf(7) + ", committed 변화=" + committedHistory);
            assertThat(position(secondDelivery)).isEqualTo(position(firstDelivery));
            assertThat(secondDelivery.offset()).isEqualTo(n);
            assertThat(results).containsExactly(ConsumeResult.RECORDED, ConsumeResult.ALREADY_PROCESSED);
            assertThat(activitiesAfterFirst).isEqualTo(2);
            assertThat(tables.activities()).isEqualTo(activitiesAfterFirst);
            assertThat(tables.quantityOf(7)).isEqualTo(quantityOf7AfterFirst);
            assertThat(tables.confirmations()).isEqualTo(1);
            assertThat(committedHistory).containsExactly(n, n, n + 1);
        } finally {
            closeAll(closeables.reversed());
        }
    }

    private ConsumeResult handle(ConsumerRecord<String, String> record) {
        byte[] payload = record.value().getBytes(StandardCharsets.UTF_8);
        OrderEventParseResult result = orderConfirmedEventParser.parse(record.key(), payload);
        assertThat(result).isInstanceOf(OrderEventParseResult.Parsed.class);
        EventSource source = new EventSource(record.topic(), record.partition(), record.offset(), record.value());
        return purchaseActivityHandler.consume(((OrderEventParseResult.Parsed) result).event(), source);
    }

    private String position(ConsumerRecord<String, String> record) {
        return "(" + record.topic() + ", " + record.partition() + ", " + record.offset() + ")";
    }

    // 배정 직후 fetch 전에 시작 위치 n을 commit해 둔다. 이후 위치를 직접 옮기지 않는다.
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
