package com.loopers.experiment;

import com.loopers.application.purchase.facade.PurchaseActivityFacade;
import com.loopers.domain.purchase.enums.PurchaseRecordResult;
import com.loopers.interfaces.consumer.OrderConfirmedConsumer;
import com.loopers.utils.DatabaseCleanUp;
import com.loopers.utils.KafkaTopics;
import jakarta.persistence.EntityManager;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.AcknowledgingMessageListener;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * 여러 partition · 많은 이벤트에서의 소비 실험. 제품 코드가 아니며 D-88(concurrency 1)을 다시 판단하는 근거로 씀.
 * <ul>
 *   <li>A: partition 3개 topic 의 확정 이벤트를 concurrency 1 · 3 · 5 로 소비해 처리 속도를 비교</li>
 *   <li>B: 소비 도중 같은 group 에 consumer 를 더하고 빼 rebalance 를 일으켜도 주문마다 정확히 한 번 저장되는지 확인</li>
 *   <li>운영 factory(ack mode · error handler · 직렬화)로 컨테이너를 직접 만들고 concurrency 만 바꿈. 실제 consumer · facade · DB 를 거침</li>
 *   <li>노트북 Docker 의 broker 1대 · MySQL 1대 수치라 절대값보다 비교에 의미를 둠</li>
 * </ul>
 * 실행: RUN_EXPERIMENT=true ./gradlew :apps:commerce-streamer:test --tests '*ThroughputExperiment'
 */
@EnabledIfEnvironmentVariable(named = "RUN_EXPERIMENT", matches = "true")
@SpringBootTest
class ThroughputExperiment {

    private static final Logger log = LoggerFactory.getLogger(ThroughputExperiment.class);
    private static final Path REPORT = Path.of("build", "experiment", "throughput.md");
    private static final int EVENTS = 10_000;
    private static final int PARTITIONS = 3;
    private static final Duration WAIT = Duration.ofMinutes(10);
    private static final List<String> report = new ArrayList<>();

    @Autowired
    private ConcurrentKafkaListenerContainerFactory<String, byte[]> orderConfirmedListenerContainerFactory;

    @Autowired
    private OrderConfirmedConsumer orderConfirmedConsumer;

    @MockitoSpyBean
    private PurchaseActivityFacade purchaseActivityFacade;

    @Autowired
    private KafkaTemplate<String, byte[]> orderConfirmedDltTemplate;

    @Autowired
    private KafkaTopics kafkaTopics;

    @Autowired
    private KafkaAdmin kafkaAdmin;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private final List<ConcurrentMessageListenerContainer<String, byte[]>> containers = new ArrayList<>();
    private final AtomicInteger recorded = new AtomicInteger();
    private final AtomicInteger alreadyRecorded = new AtomicInteger();
    private final AtomicLong firstAt = new AtomicLong();
    private final AtomicLong lastAt = new AtomicLong();

    @AfterEach
    void tearDown() throws IOException {
        containers.forEach(ConcurrentMessageListenerContainer::stop);
        containers.clear();
        kafkaTopics.deleteCreated();
        databaseCleanUp.truncateAllTables();
        Files.createDirectories(REPORT.getParent());
        Files.writeString(REPORT, "# 처리량 · rebalance 실험\n\n" + String.join("\n", report) + "\n");
    }

    /** facade 결과를 세고 처리 구간(첫 처리 ~ 마지막 처리)을 기록함 */
    private void countResults() {
        recorded.set(0);
        alreadyRecorded.set(0);
        firstAt.set(0);
        lastAt.set(0);
        doAnswer(invocation -> {
            PurchaseRecordResult result = (PurchaseRecordResult) invocation.callRealMethod();
            long now = System.nanoTime();
            firstAt.compareAndSet(0, now);
            lastAt.set(now);
            (result == PurchaseRecordResult.RECORDED ? recorded : alreadyRecorded).incrementAndGet();
            return result;
        }).when(purchaseActivityFacade).record(any());
    }

    /** 만든 직후의 topic 은 producer 가 metadata 를 몰라 실패할 수 있어, 결과를 확인하고 실패한 것만 다시 보냄 */
    private void publish(String topic, long firstOrderId) throws Exception {
        List<Long> pending = new ArrayList<>();
        for (long orderId = firstOrderId; orderId < firstOrderId + EVENTS; orderId++) {
            pending.add(orderId);
        }
        for (int round = 0; round < 5 && !pending.isEmpty(); round++) {
            Map<Long, CompletableFuture<?>> sent = new LinkedHashMap<>();
            for (long orderId : pending) {
                String value = """
                    {"eventType":"OrderConfirmed","schemaVersion":1,"eventId":"order-confirmed:v1:%d","orderId":%d,
                     "confirmedAt":"2026-09-07T01:00:00.123456Z","items":[{"productId":7,"quantity":1},{"productId":8,"quantity":2}]}
                    """.formatted(orderId, orderId);
                sent.put(orderId, orderConfirmedDltTemplate.send(topic, String.valueOf(orderId), value.getBytes(StandardCharsets.UTF_8)));
            }
            orderConfirmedDltTemplate.flush();
            List<Long> failed = new ArrayList<>();
            sent.forEach((orderId, future) -> {
                try {
                    future.get(30, TimeUnit.SECONDS);
                } catch (Exception e) {
                    failed.add(orderId);
                }
            });
            pending = failed;
            if (!pending.isEmpty()) {
                Thread.sleep(1000);
            }
        }
        assertThat(pending).as("발행하지 못한 이벤트").isEmpty();
    }

    private ConcurrentMessageListenerContainer<String, byte[]> start(String topic, String group, int concurrency) {
        ConcurrentMessageListenerContainer<String, byte[]> container = orderConfirmedListenerContainerFactory.createContainer(topic);
        container.getContainerProperties().setGroupId(group);
        container.getContainerProperties().setMessageListener(
            (AcknowledgingMessageListener<String, byte[]>) (record, ack) -> orderConfirmedConsumer.consume(record, ack));
        container.setConcurrency(concurrency);
        container.start();
        containers.add(container);
        return container;
    }

    private long count(String sql) {
        return transactionTemplate.execute(status -> ((Number) entityManager.createNativeQuery(sql).getSingleResult()).longValue());
    }

    /** topic 에 실제로 쌓인 record 수. 재전송 중복이 섞일 수 있어 이벤트 수와 다를 수 있음 */
    private long published(String topic) {
        try (AdminClient admin = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            Map<TopicPartition, OffsetSpec> latest = new java.util.HashMap<>();
            for (int partition = 0; partition < PARTITIONS; partition++) {
                latest.put(new TopicPartition(topic, partition), OffsetSpec.latest());
            }
            return admin.listOffsets(latest).all().get(10, TimeUnit.SECONDS).values().stream()
                .mapToLong(ListOffsetsResult.ListOffsetsResultInfo::offset).sum();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private long lag(String topic, String group) {
        try (AdminClient admin = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            Map<TopicPartition, OffsetAndMetadata> committed = admin.listConsumerGroupOffsets(group)
                .partitionsToOffsetAndMetadata().get(10, TimeUnit.SECONDS);
            long committedSum = committed.values().stream().mapToLong(OffsetAndMetadata::offset).sum();
            return published(topic) - committedSum;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void waitUntil(String what, BooleanSupplier condition) throws InterruptedException {
        Instant deadline = Instant.now().plus(WAIT);
        while (!condition.getAsBoolean()) {
            assertThat(Instant.now()).as(what).isBefore(deadline);
            Thread.sleep(200);
        }
    }

    private static String assignment(ConcurrentMessageListenerContainer<String, byte[]> container) {
        return container.getAssignmentsByClientId().values().stream()
            .map(partitions -> partitions.stream().map(TopicPartition::partition).sorted().toList().toString())
            .toList().toString();
    }

    @DisplayName("A: partition 3개에서 concurrency 1 · 3 · 5 의 처리 속도")
    @Test
    void throughputByConcurrency() throws Exception {
        report.add("## A: concurrency 별 처리 속도 (이벤트 " + EVENTS + "건, partition " + PARTITIONS + ", 이벤트당 품목 2)\n");
        report.add("| concurrency | consumer 별 배정 | 발행된 record | 처리 구간(s) | 초당 처리(record) | 저장 건수 | 재전달 · 중복(no-op) |");
        report.add("|---|---|---|---|---|---|---|");
        long orderId = 1;
        for (int concurrency : new int[] {1, 3, 5}) {
            countResults();
            String topic = kafkaTopics.create("throughput", PARTITIONS);
            String group = "throughput-" + UUID.randomUUID();
            publish(topic, orderId);
            long records = published(topic);
            long from = orderId;
            ConcurrentMessageListenerContainer<String, byte[]> container = start(topic, group, concurrency);
            // 중복 record 가 섞여도 서로 다른 주문이 모두 저장되고 쌓인 record 를 모두 지날 때까지 기다림
            waitUntil("concurrency " + concurrency + " 처리 완료", () -> count("select count(*) from processed_order_confirmation where order_id between "
                + from + " and " + (from + EVENTS - 1)) >= EVENTS && lag(topic, group) == 0);
            double seconds = (lastAt.get() - firstAt.get()) / 1_000_000_000.0;
            report.add(String.format("| %d | %s | %d | %.1f | %.0f | %d | %d |", concurrency, assignment(container), records, seconds,
                (recorded.get() + alreadyRecorded.get()) / seconds, recorded.get(), alreadyRecorded.get()));
            log.info("[처리량] {}", report.get(report.size() - 1));
            assertThat(count("select count(*) from processed_order_confirmation where order_id between " + orderId
                + " and " + (orderId + EVENTS - 1))).isEqualTo(EVENTS);
            container.stop();
            containers.remove(container);
            orderId += EVENTS;
        }
    }

    @DisplayName("B: 소비 도중 consumer 를 더하고 빼 rebalance 가 나도 주문마다 정확히 한 번 저장된다")
    @Test
    void exactlyOnceEffectUnderRebalance() throws Exception {
        countResults();
        String topic = kafkaTopics.create("rebalance", PARTITIONS);
        String group = "rebalance-" + UUID.randomUUID();
        publish(topic, 1);

        // 인스턴스 하나(concurrency 1)로 시작 → 30% 에서 다른 인스턴스(concurrency 2) 합류 → 60% 에서 첫 인스턴스 이탈
        ConcurrentMessageListenerContainer<String, byte[]> first = start(topic, group, 1);
        waitUntil("30% 처리", () -> recorded.get() >= EVENTS * 3 / 10);
        String beforeJoin = assignment(first);
        ConcurrentMessageListenerContainer<String, byte[]> second = start(topic, group, 2);
        waitUntil("합류 뒤 재배정", () -> !second.getAssignmentsByClientId().isEmpty()
            && second.getAssignmentsByClientId().values().stream().anyMatch(partitions -> !partitions.isEmpty()));
        String afterJoin = assignment(first) + " + " + assignment(second);
        waitUntil("60% 처리", () -> recorded.get() >= EVENTS * 6 / 10);
        first.stop();
        containers.remove(first);
        waitUntil("이탈 뒤 재배정", () -> second.getAssignmentsByClientId().values().stream().mapToInt(Collection::size).sum() == PARTITIONS);
        String afterLeave = assignment(second);
        waitUntil("전부 처리", () -> count("select count(*) from processed_order_confirmation") >= EVENTS);
        waitUntil("offset 끝까지 commit", () -> lag(topic, group) == 0);

        long processed = count("select count(*) from processed_order_confirmation");
        long distinctOrders = count("select count(distinct order_id) from processed_order_confirmation");
        long activities = count("select count(*) from product_purchase_activity");
        long quantity = count("select sum(quantity) from product_purchase_activity");
        report.add("\n## B: 소비 중 rebalance (이벤트 " + EVENTS + "건, partition " + PARTITIONS + ")\n");
        report.add("| 항목 | 값 |");
        report.add("|---|---|");
        report.add("| 배정 | 시작 " + beforeJoin + " → 합류 뒤 " + afterJoin + " → 이탈 뒤 " + afterLeave + " |");
        report.add("| 발행된 record | " + published(topic) + " |");
        report.add("| facade 호출 | 저장 " + recorded.get() + " · 이미 처리(재전달 · 중복) " + alreadyRecorded.get() + " |");
        report.add("| 처리 기록 · 서로 다른 주문 | " + processed + " · " + distinctOrders + " |");
        report.add("| 구매 활동 행 · 수량 합 | " + activities + " · " + quantity + " (기대 " + EVENTS * 2 + " · " + EVENTS * 3 + ") |");
        report.add("| 남은 lag | " + lag(topic, group) + " |");
        log.info("[rebalance]\n{}", String.join("\n", report));
        assertThat(processed).isEqualTo(EVENTS);
        assertThat(distinctOrders).isEqualTo(EVENTS);
        assertThat(activities).isEqualTo(EVENTS * 2L);
        assertThat(quantity).isEqualTo(EVENTS * 3L);
    }
}
