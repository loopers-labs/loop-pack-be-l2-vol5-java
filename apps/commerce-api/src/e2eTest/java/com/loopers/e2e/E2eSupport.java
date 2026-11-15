package com.loopers.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.support.kafka.BrokerRecords;
import com.loopers.support.pg.PaymentTestSupport;
import com.loopers.support.pg.TestPgTransaction;
import com.loopers.testcontainers.KafkaBrokerFixture;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.assertj.core.groups.Tuple;
import org.awaitility.core.ConditionTimeoutException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * E2E 테스트가 함께 쓰는 단계: 실제 controller 호출, 시한이 있는 consumer 배정·결과 대기, consumer group의 commit 위치, 구매 활동 쪽 DB 조회.
 * 대기는 시한을 넘기면 통과로 세지 않고, 그 순간의 실제 상태(consumer·broker·Outbox·구매 활동)를 실패 메시지에 남긴다 (W7 과제 8절).
 */
final class E2eSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private E2eSupport() {
    }

    // ---- HTTP (실제 controller) ----

    /** 기존 주문 API로 주문을 만들고 id를 돌려준다. {@code items}는 상품 id → 수량이며 적은 순서대로 보낸다. */
    static Long createOrder(MockMvc mvc, long userId, Map<Long, Integer> items) throws Exception {
        StringJoiner lines = new StringJoiner(",", "{\"items\":[", "]}");
        items.forEach((productId, quantity) -> lines.add("{\"productId\":" + productId + ",\"quantity\":" + quantity + "}"));
        String response = mvc.perform(post("/api/v1/orders")
                .header("X-USER-ID", userId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(lines.toString()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        JsonNode id = MAPPER.readTree(response).path("data").path("id");
        assertThat(id.isNumber()).as("주문 생성 응답의 id: %s", response).isTrue();
        return id.asLong();
    }

    /** PG 결제를 시작한다. */
    static ResultActions confirm(MockMvc mvc, long userId, Long orderId) throws Exception {
        return mvc.perform(post("/api/v1/orders/" + orderId + "/confirm")
            .header("X-USER-ID", userId)
            .contentType(MediaType.APPLICATION_JSON)
            .content(PaymentTestSupport.confirmBodyOf(orderId)));
    }

    /** PG가 이 거래의 현재 상태로 보내는 결과 알림. */
    static ResultActions callbackOf(MockMvc mvc, TestPgTransaction transaction) throws Exception {
        String body = "{\"transactionKey\":\"" + transaction.transactionKey() + "\",\"orderId\":\"" + transaction.orderId()
            + "\",\"status\":\"" + transaction.status().name() + "\"}";
        return mvc.perform(post("/api/v1/payments/callback").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    // ---- 시한이 있는 대기: 넘기면 그 순간의 실제 상태를 남기고 실패한다 ----

    /** consumer가 topic partition을 배정받을 때까지 기다린다. */
    static void awaitAssigned(MessageListenerContainer consumer, Duration deadline) {
        try {
            await().atMost(deadline).until(() -> consumer.isRunning()
                && consumer.getAssignedPartitions() != null && !consumer.getAssignedPartitions().isEmpty());
        } catch (ConditionTimeoutException e) {
            throw new AssertionError("시한(" + deadline + ") 안에 consumer가 partition을 배정받지 못했습니다. running="
                + consumer.isRunning() + ", assigned=" + consumer.getAssignedPartitions(), e);
        }
    }

    /** consumer group의 commit 위치가 {@code expected}가 될 때까지 기다린다. 넘기면 {@code state}가 만든 실제 상태를 남긴다. */
    static void awaitCommittedOffset(String group, String topic, long expected, Duration deadline, Supplier<String> state) {
        try {
            await().atMost(deadline).untilAsserted(() -> assertThat(committedOffset(group, topic)).isEqualTo(expected));
        } catch (ConditionTimeoutException e) {
            throw new AssertionError("시한(" + deadline + ") 안에 consumer가 offset " + expected + "까지 처리하지 못했습니다.\n" + state.get(), e);
        }
    }

    /** 실패한 단계의 실제 상태: consumer·broker·Outbox·소비 완료·구매 활동. 조회 하나가 실패해도 나머지를 남긴다. */
    static String describe(String group, String topic, MessageListenerContainer consumer, JdbcTemplate jdbcTemplate, List<Long> orderIds) {
        StringBuilder state = new StringBuilder("[실제 상태]");
        line(state, "consumer", () -> "running=" + consumer.isRunning() + ", assigned=" + consumer.getAssignedPartitions());
        line(state, "committed offset", () -> String.valueOf(committedOffset(group, topic)));
        line(state, "broker records", () -> BrokerRecords.readAll(topic).stream()
            .map(r -> "offset=" + r.offset() + " key=" + r.key())
            .toList()
            .toString());
        for (Long orderId : orderIds) {
            line(state, "order " + orderId + " outbox", () -> jdbcTemplate.queryForList(
                "select event_id, status, attempt_count, last_error from order_outbox where order_id = ?", orderId).toString());
            line(state, "order " + orderId + " processed", () -> processedEventIds(jdbcTemplate, orderId).toString());
            line(state, "order " + orderId + " activities", () -> purchaseActivities(jdbcTemplate, orderId).toString());
        }
        return state.toString();
    }

    private interface Probe {
        String read() throws Exception;
    }

    private static void line(StringBuilder state, String label, Probe probe) {
        String value;
        try {
            value = probe.read();
        } catch (Exception e) {
            value = "조회 실패: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        state.append("\n  ").append(label).append(": ").append(value);
    }

    // ---- broker ----

    /** consumer group이 topic의 partition 0에 commit한 offset. listener는 DB commit 뒤에만 ack하므로 처리 완료한 record 수와 같다. 없으면 null. */
    static Long committedOffset(String group, String topic) throws Exception {
        try (AdminClient admin = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaBrokerFixture.bootstrapServers()))) {
            Map<TopicPartition, OffsetAndMetadata> offsets = admin.listConsumerGroupOffsets(group)
                .partitionsToOffsetAndMetadata()
                .get(30, TimeUnit.SECONDS);
            OffsetAndMetadata committed = offsets.get(new TopicPartition(topic, 0));
            return committed == null ? null : committed.offset();
        }
    }

    static String eventIdIn(String recordValue) throws Exception {
        return MAPPER.readTree(recordValue).get("eventId").asText();
    }

    // ---- 구매 활동 쪽 DB (commerce-streamer 테이블) ----

    /** 주문의 구매 활동 (상품 id, 수량), 상품 id 순. */
    static List<Tuple> purchaseActivities(JdbcTemplate jdbcTemplate, Long orderId) {
        return jdbcTemplate.query(
            "select product_id, quantity from product_purchase_activity where order_id = ? order by product_id",
            (rs, rowNum) -> tuple(rs.getLong("product_id"), rs.getInt("quantity")),
            orderId
        );
    }

    /** 주문의 소비 완료 기록에 남은 eventId들. */
    static List<String> processedEventIds(JdbcTemplate jdbcTemplate, Long orderId) {
        return jdbcTemplate.queryForList("select event_id from processed_order_confirmation where order_id = ?", String.class, orderId);
    }
}
