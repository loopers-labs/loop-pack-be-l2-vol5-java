package com.loopers.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.support.pg.PaymentTestSupport;
import com.loopers.support.pg.TestPgTransaction;
import com.loopers.testcontainers.KafkaBrokerFixture;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.assertj.core.groups.Tuple;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** E2E 테스트가 함께 쓰는 단계: 실제 controller 호출, consumer group의 commit 위치, 구매 활동 쪽 DB 조회. */
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
