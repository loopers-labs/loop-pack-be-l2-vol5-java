package com.loopers.domains.order.in;

import com.fasterxml.jackson.databind.JsonNode;
import com.loopers.domains.payment.out.TestPgServer.CreateBehavior;
import com.loopers.support.kafka.OrderEventsProbe;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 과제 실험 ④: DB commit과 Kafka send 사이의 누락 구간. 두 사례 모두 고정 Clock(2026-10-29T01:00:00Z)과
// 같은 품목(첫 상품×2, 둘째 상품×1)으로 기존 주문 API → 테스트 PG → 결과 반영 경로를 거친다. 결과는 docs/week6/order-event-gap-cases.md.
class OrderConfirmedGapCaseTest extends OrderEventsIntegrationSupport {
    // streamer OrderConfirmedGapCaseConsumedTest가 실제 listener로 소비하는 값. 사례 B broker record와 같음을 여기서 확인한다.
    private static final String CASE_B_FIXTURE = "contracts/order-confirmed-gap-case-b.json";
    private static final String CASE_B_KEY = "1";

    // 7주차 2단계(relay-2 ①): send는 relay가 하므로 "commit 뒤 send 전"은 relay가 아직 회차를 돌리지 않은 상태다.
    // 6주차 원래 장면(직접 send 직전 멈춤)은 5단계에서 6주차 revision으로 다시 재현한다.
    @Test
    @DisplayName("사례 A: 확정 commit 뒤 relay가 아직 보내지 않았으면 주문은 CONFIRMED·예약 소비로 남고 Outbox는 PENDING 1행이며 그 eventId의 record는 broker에 없다")
    void caseAConfirmedButNotPublished() throws Exception {
        long orderId = newOrder();
        server.createBehavior(CreateBehavior.ACCEPT_SUCCESS);

        confirm(orderId, UUID.randomUUID().toString()).andExpect(status().isOk());

        Map<String, Object> state = state(orderId);
        assertThat(state).containsEntry("status", "CONFIRMED").containsEntry("result", "SUCCESS");
        assertThat(stock(first)).isEqualTo(3);
        assertThat(stock(second)).isEqualTo(4);
        assertThat(jdbc.queryForObject("select count(*) from order_outbox where order_id = ?", Long.class, orderId)).isEqualTo(1);
        assertThat(outboxRow(orderId)).containsEntry("status", "PENDING");
        List<String> eventIds = eventIdsOnBroker();
        assertThat(eventIds).doesNotContain(eventId(orderId));

        System.out.println("[실험④ 사례 A] " + describe(orderId)
            + ", 재고(첫/둘째)=3/4, Outbox=PENDING 1행(relay 회차 전), 끝 위치까지 읽은 eventId=" + eventIds
            + ", broker 상태=해당 eventId record 없음");
    }

    @Test
    @DisplayName("사례 B: 같은 방식으로 확정하고 relay 회차가 broker까지 보내면 그 eventId의 record가 (topic, partition, offset)에 1건 있다")
    void caseBConfirmedAndPublished() throws Exception {
        long orderId = newOrder();
        server.createBehavior(CreateBehavior.ACCEPT_SUCCESS);

        confirm(orderId, UUID.randomUUID().toString()).andExpect(status().isOk());
        relay.runOnce();

        ConsumerRecord<String, String> record = probe.awaitEvent(from, eventId(orderId));
        JsonNode message = OrderEventsProbe.json(record);
        assertThat(state(orderId)).containsEntry("status", "CONFIRMED").containsEntry("result", "SUCCESS");
        assertThat(stock(first)).isEqualTo(3);
        assertThat(stock(second)).isEqualTo(4);
        assertThat(record.key()).isEqualTo(String.valueOf(orderId));
        assertThat(message.path("confirmedAt").asText()).isEqualTo("2026-10-29T01:00:00Z");
        assertThat(message).isEqualTo(OrderEventsProbe.json(caseBFixture()));
        assertThat(record.key()).isEqualTo(CASE_B_KEY);

        System.out.println("[실험④ 사례 B] " + describe(orderId) + ", 재고(첫/둘째)=3/4, broker record=(topic=" + record.topic()
            + ", partition=" + record.partition() + ", offset=" + record.offset() + ", key=" + record.key() + "), value="
            + record.value());
    }

    private static String caseBFixture() throws IOException {
        try (InputStream input = new ClassPathResource(CASE_B_FIXTURE).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private Map<String, Object> state(long orderId) {
        return jdbc.queryForMap("select o.status, o.confirmed_at, p.result, p.provider_status, p.provider_order_id,"
            + " p.transaction_key from orders o join payment_records p on p.order_id = o.id where o.id = ?", orderId);
    }

    private String describe(long orderId) {
        Map<String, Object> state = state(orderId);
        List<Map<String, Object>> items = jdbc.queryForList(
            "select product_id, quantity from order_items where order_id = ? order by product_id", orderId);
        return "orderId=" + orderId + ", providerOrderId=" + state.get("provider_order_id") + ", transactionKey="
            + state.get("transaction_key") + ", eventId=" + eventId(orderId) + ", confirmedAt=" + state.get("confirmed_at")
            + "(UTC), items=" + items + ", 주문=" + state.get("status") + ", 결제 결과=" + state.get("result") + "/"
            + state.get("provider_status");
    }
}
