package com.loopers.interfaces.api.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.loopers.application.order.OrderConfirmedPayload;
import com.loopers.infrastructure.payment.TestPgServer.CreateBehavior;
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

    @Test
    @DisplayName("사례 A: commit 뒤 send 직전에 멈추면 주문은 CONFIRMED·예약 소비로 남지만 그 eventId의 record는 broker에 없다")
    void caseAConfirmedButNotPublished() throws Exception {
        long orderId = newOrder();
        server.createBehavior(CreateBehavior.ACCEPT_SUCCESS);
        publisher.haltBeforeSend();

        confirm(orderId, UUID.randomUUID().toString()).andExpect(status().isOk());

        assertThat(publisher.halted()).extracting(OrderConfirmedPayload::eventId).containsExactly(eventId(orderId));
        assertThat(publisher.sent()).isEmpty();
        Map<String, Object> state = state(orderId);
        assertThat(state).containsEntry("status", "CONFIRMED").containsEntry("result", "SUCCESS");
        assertThat(stock(first)).isEqualTo(3);
        assertThat(stock(second)).isEqualTo(4);
        publisher.reset();
        List<String> eventIds = eventIdsThroughBoundary();
        assertThat(eventIds).doesNotContain(eventId(orderId));

        System.out.println("[실험④ 사례 A] " + describe(orderId)
            + ", 재고(첫/둘째)=3/4, send 직전 멈춘 payload=" + eventId(orderId) + ", 경계까지 읽은 eventId=" + eventIds
            + ", broker 상태=해당 eventId record 없음");
    }

    @Test
    @DisplayName("사례 B: 같은 방식으로 확정하고 broker까지 보내면 그 eventId의 record가 (topic, partition, offset)에 1건 있다")
    void caseBConfirmedAndPublished() throws Exception {
        long orderId = newOrder();
        server.createBehavior(CreateBehavior.ACCEPT_SUCCESS);

        confirm(orderId, UUID.randomUUID().toString()).andExpect(status().isOk());

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
