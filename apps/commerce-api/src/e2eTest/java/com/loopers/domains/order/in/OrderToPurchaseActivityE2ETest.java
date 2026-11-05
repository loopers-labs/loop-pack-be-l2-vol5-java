package com.loopers.domains.order.in;

import com.loopers.E2eApiTestApplication;
import com.loopers.E2eStreamerContext;
import com.loopers.E2eStreamerContext.ConsumeObservation;
import com.loopers.E2eStreamerContext.ConsumeObservation.Consumed;
import com.loopers.application.purchase.ConsumeResult;
import com.loopers.domains.order.app.OrderOutboxRelay;
import com.loopers.domains.order.app.port.OutboxRelaySettings;
import com.loopers.domains.payment.out.TestPgServer.CreateBehavior;
import com.loopers.interfaces.consumer.OrderEventsListener;
import com.loopers.support.kafka.OrderEventsProbe;
import com.loopers.support.kafka.OrderEventsTestConfig.FailingOrderConfirmedOutbox;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
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
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 7주차 4단계(과제 7장): 주문 API → 로컬 PG → 확정·Outbox → relay → broker → 실제 streamer consumer → 구매 활동까지 한 JVM에서 본다.
// 중단은 예외로 회차를 끊어 재현한 것이다(3단계 markPublished hook). OS 프로세스 종료는 흉내 내지 않는다.
// streamer는 같은 JVM의 별도 컨텍스트(E2eStreamerContext)로 띄운다. 두 앱이 다른 JVM·커넥션 풀일 때의 타이밍은 증명하지 못한다.
// 대역은 markPublished hook과 handler 관찰 래퍼뿐이고, MySQL·Kafka·relay·Outbox 어댑터·listener·parser·handler는 실제다.
@SpringBootTest(classes = E2eApiTestApplication.class)
class OrderToPurchaseActivityE2ETest extends OrderEventsIntegrationSupport {
    private static final Duration ASSIGNMENT_DEADLINE = Duration.ofSeconds(30);
    private static final Duration RESULT_DEADLINE = Duration.ofSeconds(60);

    @Autowired private OutboxRelaySettings outboxRelaySettings;
    @Autowired private TransactionTemplate transactionTemplate;
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
    @DisplayName("예외로 회차를 끊어 재현: PUBLISHED 저장 전에 끊겨 같은 주문 확정이 두 번 발행돼도 구매 활동은 상품별 1행씩 한 번만 남는다")
    void duplicatePublishLeavesPurchaseActivityOnce() throws Exception {
        // 1) 첫 상품 2개·둘째 상품 1개를 주문하고 로컬 PG 성공으로 주문 API에서 확정한다.
        long orderId = newOrder();
        server.createBehavior(CreateBehavior.ACCEPT_SUCCESS);
        confirm(orderId, UUID.randomUUID().toString()).andExpect(status().isOk());
        String eventId = eventId(orderId);

        // 2) 확정·예약 소비·Outbox 1행 PENDING. 재고·포인트는 이 시점 값과 마지막에 비교한다.
        assertThat(orderStatus(orderId)).isEqualTo("CONFIRMED");
        int firstStock = stock(first);
        int secondStock = stock(second);
        assertThat(firstStock).isEqualTo(3);
        assertThat(secondStock).isEqualTo(4);
        List<Map<String, Object>> pointsAfterConfirm = points();
        assertThat(outboxRows(orderId)).singleElement().satisfies(row -> assertThat(row)
            .containsEntry("event_id", eventId).containsEntry("status", "PENDING"));

        // 배정 전에 보내도 earliest라 읽히지만, 배정 실패와 처리 실패를 구분하려고 발행 전에 배정부터 확인한다.
        MessageListenerContainer listener = streamer.getBean(KafkaListenerEndpointRegistry.class)
            .getListenerContainer(OrderEventsListener.LISTENER_ID);
        awaitUntil("consumer topic partition 배정", ASSIGNMENT_DEADLINE, () -> assignedToTopic(listener),
            () -> assignmentState(listener));

        // 3) 1차: broker 확인 뒤 PUBLISHED 기록 전에 예외로 회차를 끊는다.
        failingOutbox.failOnNextMarkPublished();
        assertThatThrownBy(relay::runOnce).hasMessage(FailingOrderConfirmedOutbox.INJECTED_MARK_PUBLISHED_FAILURE);
        List<RecordMetadata> acked = failingKafka.acked();
        assertThat(acked).hasSize(1);

        // 4) 새 relay 인스턴스로 다시 보낸다.
        new OrderOutboxRelay(failingOutbox, sender, outboxRelaySettings, transactionTemplate, clock).runOnce();

        // 5) consumer가 두 record를 모두 처리할 때까지 기다린다(의도된 동기화가 아니라 다른 컨텍스트의 결과 대기).
        awaitUntil("consumer가 같은 eventId record 2건 처리", RESULT_DEADLINE,
            () -> consumedOf(eventId).stream().map(Consumed::offset).distinct().count() >= 2, () -> state(orderId));

        assertThat(jdbc.queryForObject("select count(*) from orders where id = ? and status = 'CONFIRMED'", Long.class, orderId))
            .isEqualTo(1L);
        assertThat(server.transactions()).filteredOn(transaction -> transaction.orderId().equals(providerOrderId(orderId)))
            .hasSize(1);
        assertThat(outboxRows(orderId)).singleElement().satisfies(row -> assertThat(row)
            .containsEntry("event_id", eventId).containsEntry("status", "PUBLISHED"));
        // 같은 record를 다시 읽은 것이 아니라 재발행으로 생긴 새 record다(첫 offset이 1차 broker 확인과 같다).
        List<ConsumerRecord<String, String>> records = recordsOf(eventId);
        assertThat(records).hasSize(2);
        assertThat(records.get(0).offset()).isEqualTo(acked.getFirst().offset());
        assertThat(records.get(1).offset()).isNotEqualTo(records.get(0).offset());
        assertThat(records.get(1).partition()).isEqualTo(records.get(0).partition());
        List<Consumed> consumed = consumedOf(eventId);
        assertThat(consumed).hasSizeGreaterThanOrEqualTo(2);
        assertThat(consumed).extracting(Consumed::offset).containsAll(records.stream().map(ConsumerRecord::offset).toList());
        assertThat(consumed).filteredOn(entry -> entry.result() == ConsumeResult.RECORDED).hasSize(1);
        assertThat(consumed).filteredOn(entry -> entry.result() != ConsumeResult.RECORDED)
            .extracting(Consumed::result).containsOnly(ConsumeResult.ALREADY_PROCESSED);
        assertThat(jdbc.queryForList("select event_id from processed_order_confirmation where order_id = ?", String.class, orderId))
            .containsExactly(eventId);
        Map<Long, Integer> activity = jdbc.queryForList(
                "select product_id, quantity from product_purchase_activity where order_id = ?", orderId).stream()
            .collect(Collectors.toMap(row -> ((Number) row.get("product_id")).longValue(), row -> ((Number) row.get("quantity")).intValue()));
        assertThat(activity).containsExactlyInAnyOrderEntriesOf(Map.of(first, 2, second, 1));
        assertThat(jdbc.queryForObject("select count(*) from product_purchase_activity where order_id = ?", Long.class, orderId))
            .isEqualTo(2L);
        // 이 PG 주문은 포인트를 쓰지 않아 points 행이 없을 수 있다. 그때 포인트 비교는 "변화 없음"만 본다.
        assertThat(stock(first)).isEqualTo(firstStock);
        assertThat(stock(second)).isEqualTo(secondStock);
        assertThat(points()).isEqualTo(pointsAfterConfirm);
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

    private String state(long orderId) {
        return "outbox=" + outboxRows(orderId)
            + ", topicEnd=" + recordsOf(eventId(orderId)).stream().map(record -> record.partition() + "@" + record.offset()).toList()
            + ", consumed=" + ConsumeObservation.CONSUMED
            + ", processed=" + jdbc.queryForObject("select count(*) from processed_order_confirmation where order_id = ?", Long.class,
                orderId)
            + ", activity=" + jdbc.queryForObject("select count(*) from product_purchase_activity where order_id = ?", Long.class,
                orderId);
    }

    private List<Consumed> consumedOf(String eventId) {
        return ConsumeObservation.CONSUMED.stream().filter(entry -> eventId.equals(entry.eventId())).toList();
    }

    private List<ConsumerRecord<String, String>> recordsOf(String eventId) {
        failingKafka.flush();
        return probe.readThrough(from).stream().filter(record -> eventId.equals(OrderEventsProbe.eventId(record))).toList();
    }

    private List<Map<String, Object>> outboxRows(long orderId) {
        return jdbc.queryForList("select event_id, status from order_outbox where order_id = ?", orderId);
    }

    private List<Map<String, Object>> points() {
        return jdbc.queryForList("select user_id, balance from points order by user_id");
    }
}
