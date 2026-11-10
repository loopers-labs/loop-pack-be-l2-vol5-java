package com.loopers.domains.order.in;

import com.loopers.E2eApiTestApplication;
import com.loopers.E2eStreamerContext;
import com.loopers.E2eStreamerContext.ConsumeObservation;
import com.loopers.E2eStreamerContext.ConsumeObservation.Consumed;
import com.loopers.application.purchase.ConsumeResult;
import com.loopers.domains.order.app.OrderConfirmedPayloadFactory;
import com.loopers.domains.order.app.OrderOutboxBackfill;
import com.loopers.domains.order.app.OrderOutboxBackfill.Outcome;
import com.loopers.domains.order.app.OrderOutboxBackfill.Result;
import com.loopers.domains.order.app.port.OrderConfirmedPayload;
import com.loopers.domains.order.app.port.OrderRepository;
import com.loopers.domains.order.out.OrderConfirmedMessageSerializer;
import com.loopers.domains.payment.out.TestPgServer.CreateBehavior;
import com.loopers.interfaces.consumer.OrderEventsListener;
import com.loopers.support.kafka.OrderEventsProbe;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 7주차 5단계(과제 5장, 축소판 D120·③): 6주차 누락 상태를 재현하고 복구 메서드(D121)로 복구한다.
// 재현은 실제 6주차 실행물이 아니라, Outbox 기록을 비운 대역(append가 아무것도 하지 않음)으로 만든 같은 상태다.
// - 주문 A: 확정 commit 뒤 send가 broker에 닿지 않은 상태(Outbox 없음·broker 0·구매 활동 없음).
// - 주문 B: 6주차 AFTER_COMMIT send를 흉내 내, 테스트가 1단계와 같은 팩토리·직렬화기로 만든 메시지를 한 번 직접 보낸 상태(broker 1·구매 활동 있음).
// 복구는 대역을 해제한 같은 컨텍스트·같은 DB에서 한다. MySQL·Kafka·relay·Outbox 어댑터·streamer consumer는 실제다.
// 정리는 모든 확인이 끝난 뒤 부모의 @AfterEach가 한다(streamer를 먼저 닫는다).
@SpringBootTest(classes = E2eApiTestApplication.class)
class OrderOutboxRecoveryE2ETest extends OrderEventsIntegrationSupport {
    private static final Duration ASSIGNMENT_DEADLINE = Duration.ofSeconds(30);
    private static final Duration RESULT_DEADLINE = Duration.ofSeconds(60);

    @Autowired private OrderOutboxBackfill orderOutboxBackfill;
    @Autowired private OrderRepository orderRepository;
    @Autowired private Environment environment;

    private ConfigurableApplicationContext streamer;

    @BeforeEach
    void startStreamer() {
        ConsumeObservation.CONSUMED.clear();
        streamer = E2eStreamerContext.start(TOPIC, bootstrapServers,
            environment.getRequiredProperty("datasource.mysql-jpa.main.jdbc-url"),
            environment.getRequiredProperty("datasource.mysql-jpa.main.username"),
            environment.getRequiredProperty("datasource.mysql-jpa.main.password"));
    }

    @AfterEach
    void stopStreamer() {
        if (streamer != null) {
            streamer.close();
        }
        ConsumeObservation.CONSUMED.clear();
    }

    @Test
    @DisplayName("Outbox 기록을 비운 대역으로 만든 6주차 누락 상태(A 미발행·B 발행됨)를 복구하면 A만 구매 활동이 새로 생기고 B는 그대로이며 PG·주문·예약은 바뀌지 않고 두 번 복구해도 같다")
    void backfillRecoversMissedConfirmationWithoutSideEffects() throws Exception {
        MessageListenerContainer listener = streamer.getBean(KafkaListenerEndpointRegistry.class)
            .getListenerContainer(OrderEventsListener.LISTENER_ID);
        awaitUntil("consumer topic partition 배정", ASSIGNMENT_DEADLINE, () -> assignedToTopic(listener),
            () -> assignmentState(listener));

        // 재현: Outbox 기록을 비운 채 주문 API로 A·B를 확정한다.
        failingOutbox.skipAppends();
        long orderA = confirmedOrder();
        long orderB = confirmedOrder();
        failingOutbox.disarm();
        String eventA = eventId(orderA);
        String eventB = eventId(orderB);

        // B만 6주차 send처럼 한 번 직접 보낸다(같은 팩토리·직렬화기, key = orderId).
        OrderConfirmedPayload payloadB = OrderConfirmedPayloadFactory.create(orderRepository.findById(orderB).orElseThrow());
        failingKafka.send(TOPIC, payloadB.key(), OrderConfirmedMessageSerializer.serialize(payloadB)).get(10, TimeUnit.SECONDS);
        awaitUntil("B 직접 발행 소비", RESULT_DEADLINE, () -> !consumedOf(eventB).isEmpty(), () -> state(orderA, orderB));

        assertThat(orderStatus(orderA)).isEqualTo("CONFIRMED");
        assertThat(orderStatus(orderB)).isEqualTo("CONFIRMED");
        assertThat(outboxRows(orderA)).isEmpty();
        assertThat(outboxRows(orderB)).isEmpty();
        assertThat(recordsOf(eventA)).isEmpty();
        assertThat(recordsOf(eventB)).hasSize(1);
        assertThat(activity(orderA)).isEmpty();
        assertThat(activity(orderB)).containsExactlyInAnyOrderEntriesOf(Map.of(first, 2, second, 1));
        assertThat(consumedOf(eventB)).extracting(Consumed::result).containsExactly(ConsumeResult.RECORDED);

        long createCallsBefore = server.createCalls();
        long lookupCallsBefore = server.lookupCalls();
        int transactionsBefore = server.transactions().size();
        List<Map<String, Object>> ordersBefore = orders(orderA, orderB);
        List<Map<String, Object>> paymentsBefore = payments(orderA, orderB);
        int firstStockBefore = stock(first);
        int secondStockBefore = stock(second);
        List<Map<String, Object>> activityRowsB = activityRows(orderB);

        // 복구 1회차: A·B 모두 Outbox 행이 없으므로 1행씩 생기고, relay가 보내면 consumer가 처리한다.
        assertThat(orderOutboxBackfill.ensure(List.of(orderA, orderB)))
            .containsExactly(new Outcome(orderA, Result.ADDED), new Outcome(orderB, Result.ADDED));
        assertThat(outboxRows(orderA)).singleElement().satisfies(row -> assertThat(row).containsEntry("status", "PENDING"));
        assertThat(outboxRows(orderB)).singleElement().satisfies(row -> assertThat(row).containsEntry("status", "PENDING"));
        relay.runOnce();
        awaitUntil("복구 발행 소비(A 1건·B 2건째)", RESULT_DEADLINE,
            () -> consumedOf(eventA).size() >= 1 && consumedOf(eventB).size() >= 2, () -> state(orderA, orderB));

        assertThat(outboxRows(orderA)).singleElement().satisfies(row -> assertThat(row).containsEntry("status", "PUBLISHED"));
        assertThat(outboxRows(orderB)).singleElement().satisfies(row -> assertThat(row).containsEntry("status", "PUBLISHED"));
        assertThat(recordsOf(eventA)).hasSize(1);
        assertThat(recordsOf(eventB)).hasSize(2);
        assertThat(consumedOf(eventA)).extracting(Consumed::result).containsExactly(ConsumeResult.RECORDED);
        assertThat(consumedOf(eventB)).extracting(Consumed::result)
            .containsExactly(ConsumeResult.RECORDED, ConsumeResult.ALREADY_PROCESSED);
        assertThat(activity(orderA)).containsExactlyInAnyOrderEntriesOf(Map.of(first, 2, second, 1));
        assertThat(activityRows(orderB)).isEqualTo(activityRowsB);
        assertNoSideEffects(createCallsBefore, lookupCallsBefore, transactionsBefore, ordersBefore, paymentsBefore,
            firstStockBefore, secondStockBefore, orderA, orderB);

        // 복구 2회차: 이미 같은 행이 있어 아무것도 바뀌지 않고, relay가 보낼 행도 없다.
        List<Map<String, Object>> outboxAfterFirst = outboxRows(orderA, orderB);
        List<Map<String, Object>> activityAfterFirst = activityRows(orderA, orderB);
        assertThat(orderOutboxBackfill.ensure(List.of(orderA, orderB)))
            .containsExactly(new Outcome(orderA, Result.ALREADY_SAME), new Outcome(orderB, Result.ALREADY_SAME));
        relay.runOnce();
        assertThat(outboxRows(orderA, orderB)).isEqualTo(outboxAfterFirst);
        assertThat(recordsOf(eventA)).hasSize(1);
        assertThat(recordsOf(eventB)).hasSize(2);
        assertThat(activityRows(orderA, orderB)).isEqualTo(activityAfterFirst);
        assertNoSideEffects(createCallsBefore, lookupCallsBefore, transactionsBefore, ordersBefore, paymentsBefore,
            firstStockBefore, secondStockBefore, orderA, orderB);
    }

    private void assertNoSideEffects(long createCallsBefore, long lookupCallsBefore, int transactionsBefore,
        List<Map<String, Object>> ordersBefore, List<Map<String, Object>> paymentsBefore, int firstStockBefore,
        int secondStockBefore, long orderA, long orderB) {
        assertThat(server.createCalls()).isEqualTo(createCallsBefore);
        assertThat(server.lookupCalls()).isEqualTo(lookupCallsBefore);
        assertThat(server.transactions()).hasSize(transactionsBefore);
        assertThat(orders(orderA, orderB)).isEqualTo(ordersBefore);
        assertThat(payments(orderA, orderB)).isEqualTo(paymentsBefore);
        assertThat(stock(first)).isEqualTo(firstStockBefore);
        assertThat(stock(second)).isEqualTo(secondStockBefore);
    }

    private long confirmedOrder() throws Exception {
        long orderId = newOrder();
        server.createBehavior(CreateBehavior.ACCEPT_SUCCESS);
        confirm(orderId, UUID.randomUUID().toString()).andExpect(status().isOk());
        return orderId;
    }

    private void awaitUntil(String step, Duration limit, BooleanSupplier condition, Supplier<String> state) {
        Instant deadline = Instant.now().plus(limit);
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) {
                fail(step + " 대기 " + limit.toSeconds() + "초 초과. 실제 상태: " + state.get());
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail(step + " 대기 중 인터럽트. 실제 상태: " + state.get());
            }
        }
    }

    private static boolean assignedToTopic(MessageListenerContainer listener) {
        Collection<TopicPartition> assigned = listener.getAssignedPartitions();
        return assigned != null && assigned.stream().anyMatch(partition -> TOPIC.equals(partition.topic()));
    }

    private static String assignmentState(MessageListenerContainer listener) {
        return "listener=" + OrderEventsListener.LISTENER_ID + ", running=" + listener.isRunning() + ", assigned="
            + listener.getAssignedPartitions() + ", topic=" + TOPIC;
    }

    private String state(long orderA, long orderB) {
        return "outbox=" + outboxRows(orderA, orderB)
            + ", recordsA=" + offsets(recordsOf(eventId(orderA))) + ", recordsB=" + offsets(recordsOf(eventId(orderB)))
            + ", consumed=" + ConsumeObservation.CONSUMED
            + ", processed=" + jdbc.queryForList("select order_id, event_id from processed_order_confirmation")
            + ", activity=" + activityRows(orderA, orderB);
    }

    private static List<String> offsets(List<ConsumerRecord<String, String>> records) {
        return records.stream().map(record -> record.partition() + "@" + record.offset()).toList();
    }

    private List<Consumed> consumedOf(String eventId) {
        return ConsumeObservation.CONSUMED.stream().filter(entry -> eventId.equals(entry.eventId())).toList();
    }

    private List<ConsumerRecord<String, String>> recordsOf(String eventId) {
        failingKafka.flush();
        return probe.readThrough(from).stream().filter(record -> eventId.equals(OrderEventsProbe.eventId(record))).toList();
    }

    private List<Map<String, Object>> outboxRows(long... orderIds) {
        return jdbc.queryForList("select order_id, event_id, status, attempt_count, payload, published_at from order_outbox"
            + " where order_id in (" + placeholders(orderIds) + ") order by order_id", (Object[]) boxed(orderIds));
    }

    private Map<Long, Integer> activity(long orderId) {
        return activityRows(orderId).stream().collect(Collectors.toMap(
            row -> ((Number) row.get("product_id")).longValue(), row -> ((Number) row.get("quantity")).intValue()));
    }

    private List<Map<String, Object>> activityRows(long... orderIds) {
        return jdbc.queryForList("select id, order_id, product_id, quantity, confirmed_at from product_purchase_activity"
            + " where order_id in (" + placeholders(orderIds) + ") order by id", (Object[]) boxed(orderIds));
    }

    private List<Map<String, Object>> orders(long... orderIds) {
        return jdbc.queryForList("select id, status, confirmed_at from orders where id in (" + placeholders(orderIds) + ") order by id",
            (Object[]) boxed(orderIds));
    }

    private List<Map<String, Object>> payments(long... orderIds) {
        return jdbc.queryForList("select id, order_id, result, provider_status, attempt_key from payment_records"
            + " where order_id in (" + placeholders(orderIds) + ") order by id", (Object[]) boxed(orderIds));
    }

    private static String placeholders(long[] values) {
        return String.join(",", Collections.nCopies(values.length, "?"));
    }

    private static Long[] boxed(long[] values) {
        return Arrays.stream(values).boxed().toArray(Long[]::new);
    }
}
