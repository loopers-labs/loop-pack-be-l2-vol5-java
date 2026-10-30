package com.loopers.interfaces.api.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.loopers.application.order.OrderConfirmedPayload;
import com.loopers.application.order.OrderConfirmedPayloadFactory;
import com.loopers.application.order.PaymentReport;
import com.loopers.application.order.port.OrderRepository;
import com.loopers.domain.payment.PaymentRecord;
import com.loopers.infrastructure.order.OrderConfirmedMessageSerializer;
import com.loopers.infrastructure.payment.TestPgServer.CreateBehavior;
import com.loopers.support.kafka.OrderEventsProbe;
import com.loopers.support.kafka.OrderEventsTestConfig.FailingPaymentRecordRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OrderConfirmedPublishIntegrationTest extends OrderEventsIntegrationSupport {
    @Autowired private OrderRepository orderRepository;
    @Autowired private ApplicationEventPublisher applicationEventPublisher;

    @Test
    @DisplayName("PG 성공으로 처음 확정하면 broker에 key=주문 ID인 OrderConfirmed 1건이 오고 payload가 저장된 주문(품목·수량·확정 시각)과 같다")
    void firstConfirmationPublishesStoredOrder() throws Exception {
        long orderId = newOrder();
        server.createBehavior(CreateBehavior.ACCEPT_SUCCESS);

        confirm(orderId, UUID.randomUUID().toString()).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("CONFIRMED"));

        ConsumerRecord<String, String> record = probe.awaitEvent(from, eventId(orderId));
        assertThat(record.key()).isEqualTo(String.valueOf(orderId));
        JsonNode message = OrderEventsProbe.json(record);
        assertThat(message.path("eventType").asText()).isEqualTo("OrderConfirmed");
        assertThat(message.path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(message.path("orderId").asLong()).isEqualTo(orderId);
        assertThat(message.path("confirmedAt").asText()).isEqualTo("2026-10-29T01:00:00Z");
        assertThat(message.path("items").toString())
            .isEqualTo("[{\"productId\":" + first + ",\"quantity\":2},{\"productId\":" + second + ",\"quantity\":1}]");
        assertThat(jdbc.queryForObject("select confirmed_at from orders where id = ?", LocalDateTime.class, orderId))
            .isEqualTo(LocalDateTime.parse("2026-10-29T01:00:00"));
        OrderConfirmedPayload stored = OrderConfirmedPayloadFactory.create(orderRepository.findById(orderId).orElseThrow());
        assertThat(message).isEqualTo(OrderEventsProbe.json(OrderConfirmedMessageSerializer.serialize(stored)));
    }

    @Test
    @DisplayName("create는 결제 대기였고 callback으로 처음 확정되면 그때 OrderConfirmed 1건을 보낸다")
    void firstConfirmationByCallbackPublishes() throws Exception {
        long orderId = newOrder();
        server.createBehavior(CreateBehavior.ACCEPT_PENDING);
        confirm(orderId, UUID.randomUUID().toString()).andExpect(status().isAccepted());
        clock.advance(Duration.ofSeconds(5));
        server.changeStatus(transactionKey(orderId), "SUCCESS");

        callback(orderId, "SUCCESS").andExpect(status().isOk());

        ConsumerRecord<String, String> record = probe.awaitEvent(from, eventId(orderId));
        assertThat(OrderEventsProbe.json(record).path("confirmedAt").asText()).isEqualTo("2026-10-29T01:00:05Z");
        assertThat(orderStatus(orderId)).isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("같은 주문의 성공이 create 응답 → callback → 고객 reconcile → 같은 키 confirm으로 반복돼도 record는 1건이고 재고를 더 차감하지 않는다")
    void repeatedSuccessPublishesOnce() throws Exception {
        long orderId = newOrder();
        String attemptKey = UUID.randomUUID().toString();
        server.createBehavior(CreateBehavior.ACCEPT_SUCCESS);
        confirm(orderId, attemptKey).andExpect(status().isOk());

        callback(orderId, "SUCCESS").andExpect(status().isOk());
        reconcile(orderId).andExpect(status().isOk());
        confirm(orderId, attemptKey).andExpect(status().isOk());

        assertThat(stock(first)).isEqualTo(3);
        assertThat(stock(second)).isEqualTo(4);
        List<String> eventIds = eventIdsThroughBoundary();
        assertThat(eventIds.stream().filter(eventId(orderId)::equals)).hasSize(1);
        assertThat(publishedEvents.published().stream().filter(payload -> payload.orderId() == orderId)).hasSize(1);
    }

    @Test
    @DisplayName("결제를 시작하지 않은 DRAFT 주문에 성공 결과가 들어오면 DRAFT 그대로이고 이벤트를 보내지 않는다")
    void draftResultPublishesNothing() throws Exception {
        long orderId = newOrder();

        orders.applyPaymentResult(orderId, new PaymentReport(PaymentRecord.ProviderStatus.SUCCESS, "TR-DRAFT"));

        assertThat(orderStatus(orderId)).isEqualTo("DRAFT");
        assertNoEvent(orderId);
    }

    @ParameterizedTest(name = "create {0} → PG 상태 {1}")
    @CsvSource({"ACCEPT_PENDING, PENDING", "DELAY_AFTER_CREATE, UNKNOWN"})
    @DisplayName("결제 대기(PENDING·결과 모름)로 끝난 confirm은 이벤트를 보내지 않는다")
    void pendingPublishesNothing(CreateBehavior behavior, String providerStatus) throws Exception {
        long orderId = newOrder();
        server.createBehavior(behavior);

        confirm(orderId, UUID.randomUUID().toString()).andExpect(status().isAccepted())
            .andExpect(jsonPath("$.data.providerStatus").value(providerStatus));

        assertThat(orderStatus(orderId)).isEqualTo("PAYMENT_PENDING");
        assertNoEvent(orderId);
    }

    @Test
    @DisplayName("PG 실패로 결제 실패가 된 주문은 이벤트를 보내지 않는다")
    void failedPublishesNothing() throws Exception {
        long orderId = newOrder();
        server.createBehavior(CreateBehavior.ACCEPT_FAILED);

        confirm(orderId, UUID.randomUUID().toString()).andExpect(status().isOk());

        assertThat(orderStatus(orderId)).isEqualTo("PAYMENT_FAILED");
        assertNoEvent(orderId);
    }

    @Test
    @DisplayName("결제 만료 뒤 늦게 성공한 callback은 충돌 기록만 남기고 결제 만료 그대로이며 이벤트를 보내지 않는다")
    void lateSuccessAfterExpiryPublishesNothing() throws Exception {
        long orderId = newOrder();
        server.createBehavior(CreateBehavior.ACCEPT_PENDING);
        confirm(orderId, UUID.randomUUID().toString()).andExpect(status().isAccepted());
        clock.advance(Duration.ofMinutes(2));
        orders.expireOverduePayments();
        server.changeStatus(transactionKey(orderId), "SUCCESS");

        callback(orderId, "SUCCESS").andExpect(status().isOk());

        assertThat(orderStatus(orderId)).isEqualTo("PAYMENT_EXPIRED");
        assertThat(jdbc.queryForObject("select count(*) from payment_anomalies where order_id = ? and kind = 'CONFLICTING_RESULT'",
            Long.class, orderId)).isEqualTo(1);
        assertNoEvent(orderId);
    }

    @Test
    @DisplayName("결과 반영 트랜잭션이 확정 이벤트 발행 뒤 rollback되면 주문은 결제 대기 그대로이고 broker로 보내지 않는다")
    void rolledBackConfirmationPublishesNothing() throws Exception {
        long orderId = newOrder();
        server.createBehavior(CreateBehavior.ACCEPT_PENDING);
        confirm(orderId, UUID.randomUUID().toString()).andExpect(status().isAccepted());
        failingPayments.failOnNextSave();

        assertThatThrownBy(() -> orders.applyPaymentResult(orderId,
            new PaymentReport(PaymentRecord.ProviderStatus.SUCCESS, transactionKey(orderId))))
            .hasMessage(FailingPaymentRecordRepository.INJECTED_FAILURE);

        assertThat(publishedEvents.published()).extracting(OrderConfirmedPayload::orderId).containsExactly(orderId);
        assertThat(publisher.sent()).isEmpty();
        assertThat(orderStatus(orderId)).isEqualTo("PAYMENT_PENDING");
        assertThat(jdbc.queryForObject("select confirmed_at from orders where id = ?", LocalDateTime.class, orderId)).isNull();
        assertNoEvent(orderId);
    }

    @Test
    @DisplayName("이관된 과거 POINT 확정 주문은 성공 결과·reconcile이 와도 이벤트를 보내지 않는다(소급 발행 없음)")
    void legacyPointConfirmedOrderPublishesNothing() throws Exception {
        long orderId = newOrder();
        jdbc.update("update orders set status = 'CONFIRMED' where id = ?", orderId);
        jdbc.update("insert into payment_records(order_id, method, amount, result) values (?, 'POINT', 7000, 'SUCCESS')", orderId);

        orders.applyPaymentResult(orderId, new PaymentReport(PaymentRecord.ProviderStatus.SUCCESS, "TR-POINT"));
        reconcile(orderId).andExpect(status().isConflict());

        assertThat(publishedEvents.published()).isEmpty();
        assertNoEvent(orderId);
    }

    @Test
    @DisplayName("트랜잭션 밖에서 발행한 확정 이벤트는 after-commit listener가 조용히 건너뛰므로 발행은 결과 반영 트랜잭션 안에서만 한다")
    void eventOutsideTransactionIsSilentlySkipped() {
        OrderConfirmedPayload payload = new OrderConfirmedPayload("99", eventId(99), 99, NOW,
            List.of(new OrderConfirmedPayload.Item(first, 1)));

        applicationEventPublisher.publishEvent(payload);

        assertThat(publishedEvents.published()).containsExactly(payload);
        assertThat(publisher.sent()).isEmpty();
    }
}
