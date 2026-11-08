package com.loopers.application.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.pgsimulator.PaymentStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * W7 전환(설계 W7.4-4, O3 의 HTTP 경로). 실제 HTTP 요청·로컬 PG fixture·결과 적용 TX 를 거친 최초 확정 뒤, 응답 이후 새 연결의 SQL 로
 * 주문 확정과 같은 사건의 Outbox PENDING 한 행이 commit 됐는지 확인한다. 자동 relay 는 아직 없으며 broker 기록 근거가 아니다.
 */
@Timeout(20)
@DisplayName("주문 확정·콜백·대사 HTTP 로 처음 확정하면 확정과 Outbox 한 행이 함께 commit 되고, 반복 요청은 둘을 바꾸지 않는다.")
class OrderConfirmedPublicationHttpIntegrationTest extends PaymentHttpTestSupport {

    @Autowired private ObjectMapper objectMapper;

    private List<Map<String, Object>> outbox() {
        return jdbc.queryForList("SELECT * FROM order_confirmed_outbox ORDER BY id");
    }

    private Instant storedConfirmedAt() {
        String text = jdbc.queryForObject(
            "SELECT DATE_FORMAT(confirmed_at, '%Y-%m-%dT%H:%i:%s.%f') FROM orders WHERE id = ?", String.class, order);
        return LocalDateTime.parse(text).toInstant(ZoneOffset.UTC);
    }

    private void assertRecordedOnceWithConfirmation(ResponseEntity<?> response, Supplier<ResponseEntity<?>> repeat)
        throws Exception {
        List<Map<String, Object>> rows = outbox();
        JsonNode payload = objectMapper.readTree((String) rows.get(0).get("payload"));
        Instant confirmedAt = storedConfirmedAt();
        ResponseEntity<?> repeated = repeat.get();
        assertAll(
            () -> assertThat(response.getStatusCode().value()).isEqualTo(200),
            () -> assertThat(detail(order).path("status").asText()).isEqualTo("CONFIRMED"),
            () -> assertThat(rows).hasSize(1),
            () -> assertThat(rows.get(0)).containsEntry("event_id", "order-confirmed:" + order)
                .containsEntry("message_key", Long.toString(order)).containsEntry("status", "PENDING"),
            () -> assertThat(payload.path("orderId").asLong()).isEqualTo(order),
            () -> assertThat(Instant.parse(payload.path("confirmedAt").asText())).isEqualTo(confirmedAt),
            () -> assertThat(payload.path("items").toString())
                .isEqualTo("[{\"productId\":" + product.getId() + ",\"quantity\":2}]"),
            () -> assertThat(histories(order, "ORDER_RESERVATION")).isEqualTo(1L),
            () -> assertThat(repeated.getStatusCode().value()).as("같은 거래 재확인").isEqualTo(200),
            () -> assertThat(outbox()).as("반복 뒤 Outbox 불변").isEqualTo(rows),
            () -> assertThat(storedConfirmedAt()).as("반복 뒤 확정 시각 불변").isEqualTo(confirmedAt),
            () -> assertThat(pg.state().counts().createReceived()).as("PG create 1회").isEqualTo(1)
        );
        noPointEffect();
    }

    private ResponseEntity<?> afterPending(Supplier<ResponseEntity<?>> apply) {
        assertThat(confirm(order).getStatusCode().value()).isEqualTo(202);
        awaitPgIdle();
        assertThat(outbox()).as("대기 중에는 Outbox 없음").isEmpty();
        pg.setStatus(key(order), PaymentStatus.SUCCESS);
        return apply.get();
    }

    @DisplayName("주문 확정 요청이 즉시 SUCCESS 를 받으면")
    @Test
    void immediateSuccess() throws Exception {
        success(order);

        assertRecordedOnceWithConfirmation(confirm(order), () -> reconcile(order));
    }

    @DisplayName("대기 뒤 콜백이 권위 조회로 SUCCESS 를 확인하면")
    @Test
    void callbackSuccess() throws Exception {
        assertRecordedOnceWithConfirmation(afterPending(() -> callback(order, key(order), "SUCCESS")),
            () -> callback(order, key(order), "SUCCESS"));
    }

    @DisplayName("대기 뒤 대사가 SUCCESS 를 확인하면")
    @Test
    void reconcileSuccess() throws Exception {
        assertRecordedOnceWithConfirmation(afterPending(() -> reconcile(order)), () -> reconcile(order));
    }
}
