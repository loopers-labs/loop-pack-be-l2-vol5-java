package com.loopers.application.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.order.OrderOutboxBackfill;
import com.loopers.application.order.OrderOutboxRelay;
import com.loopers.application.order.OrderOutboxRelayProperties;
import com.loopers.domain.order.OrderConfirmedEvent;
import com.loopers.domain.order.OrderEventPublication;
import com.loopers.domain.order.OrderEventSerializer;
import com.loopers.domain.order.OrderOutboxModel;
import com.loopers.domain.order.OrderOutboxService;
import com.loopers.domain.order.OrderOutboxStatus;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.order.ReservationStatus;
import com.loopers.support.kafka.RecordingOrderEventPublisher;
import com.loopers.support.kafka.W6DirectPublishConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * 누락 복구 경로({@link OrderOutboxBackfill})를 실제 MySQL로 확인한다 (W7 과제 5절, design.md 4-4절).
 *
 * <p>Outbox 없이 확정된 주문은 {@link W6DirectPublishConfig}로 W6의 "commit 뒤 직접 발행" 경로를 실행해 만든다(발행기는 기록만 한다).
 * W7 경로로 만든 행을 지워 누락을 꾸미지 않는다. 충돌 장면 하나만 판정 확인을 위해 다른 사실의 행을 직접 넣는다.
 */
@Import(W6DirectPublishConfig.class)
class OrderOutboxBackfillTest extends PaymentScenarioTestBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private OrderOutboxBackfill backfill;

    @Autowired
    private OrderOutboxService orderOutboxService;

    @Autowired
    private OrderEventSerializer orderEventSerializer;

    @Autowired
    private RecordingOrderEventPublisher publisher;

    @BeforeEach
    void clearPublisher() {
        publisher.clear();
    }

    private OrderOutboxModel onlyRow() {
        List<OrderOutboxModel> rows = outboxRows();
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    @DisplayName("Outbox 없이 확정된 주문은 저장된 확정 사실(eventId·confirmedAt·품목) 그대로 PENDING 한 행을 얻는다. 복구 시각은 payload에 들어가지 않고, PG·주문·예약은 그대로다.")
    @Test
    void addsPendingRowFromStoredFacts_whenOutboxMissing() throws Exception {
        // arrange: W6 경로로 확정한다. 직접 발행은 기록만 되고 Outbox 행은 없다
        confirmOrderThroughCallback(orderId);
        assertThat(outboxRows()).isEmpty();
        assertThat(publisher.events()).hasSize(1);
        String sentByW6 = orderEventSerializer.serialize(publisher.events().get(0));
        Instant confirmedAt = orderJpaRepository.findById(orderId).orElseThrow().getConfirmedAt();
        int pgCreates = pg.createCount();
        int stockBefore = stock();
        Instant before = Instant.now();

        // act
        List<OrderOutboxBackfill.Outcome> outcomes = backfill.backfill(List.of(orderId));

        // assert
        String eventId = OrderConfirmedEvent.eventIdOf(orderId);
        assertThat(outcomes).containsExactly(new OrderOutboxBackfill.Outcome(orderId, eventId, OrderOutboxBackfill.Result.ADDED, null));
        OrderOutboxModel row = onlyRow();
        assertThat(row.getEventId()).isEqualTo(eventId);
        assertThat(row.getStatus()).isEqualTo(OrderOutboxStatus.PENDING);
        assertThat(row.getAttemptCount()).isZero();
        assertThat(row.getPayload()).as("W6가 그때 보내려던 사실과 같은 바이트").isEqualTo(sentByW6);
        JsonNode payload = MAPPER.readTree(row.getPayload());
        assertThat(payload.get("confirmedAt").asText()).as("복구 시각이 아니라 저장된 확정 시각").isEqualTo(confirmedAt.toString());
        assertThat(row.getNextAttemptAt()).as("복구한 때부터 relay 대상").isAfterOrEqualTo(before.truncatedTo(ChronoUnit.MILLIS));
        assertThat(pg.createCount()).isEqualTo(pgCreates);
        assertThat(stock()).isEqualTo(stockBefore);
        assertThat(order().status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(reservation()).isEqualTo(ReservationStatus.CONSUMED);
    }

    @DisplayName("같은 대상을 다시 복구하면 행이 늘지 않고, 이미 보낸 행도 다시 PENDING으로 돌리지 않는다.")
    @Test
    void keepsExistingRow_whenBackfilledAgain() throws Exception {
        // arrange: 복구한 행을 relay가 보내 PUBLISHED로 만든다(broker 대신 확인을 돌려주는 발행기)
        confirmOrderThroughCallback(orderId);
        backfill.backfill(List.of(orderId));
        OrderOutboxRelayProperties properties =
            new OrderOutboxRelayProperties(false, Duration.ofSeconds(1), 50, Duration.ofSeconds(1), 2.0, Duration.ofMinutes(5), 5);
        new OrderOutboxRelay(orderOutboxService, message -> new OrderEventPublication.Sent("test", 0, 0L), properties,
            Clock.fixed(Instant.now().plusSeconds(1), ZoneOffset.UTC)).runOnce();
        OrderOutboxModel published = onlyRow();
        assertThat(published.getStatus()).isEqualTo(OrderOutboxStatus.PUBLISHED);

        // act
        List<OrderOutboxBackfill.Outcome> again = backfill.backfill(List.of(orderId));

        // assert
        assertThat(again).extracting(OrderOutboxBackfill.Outcome::result).containsExactly(OrderOutboxBackfill.Result.ALREADY_PRESENT);
        OrderOutboxModel row = onlyRow();
        assertThat(row.getId()).isEqualTo(published.getId());
        assertThat(row.getStatus()).isEqualTo(OrderOutboxStatus.PUBLISHED);
        assertThat(row.getAttemptCount()).isEqualTo(1);
        assertThat(row.getPublishedAt()).isEqualTo(published.getPublishedAt());
    }

    @DisplayName("원본 사실이 부족한 대상(없는 주문·미확정 주문)은 추측해 채우지 않고 거부하며, 다른 대상은 그대로 처리한다.")
    @Test
    void rejectsTargetsWithoutConfirmedFacts_andProcessesOthers() throws Exception {
        // arrange: orderId는 결제 전 주문, confirmedOrderId만 W6 경로로 확정한다
        Long confirmedOrderId = saveDraft(USER_ID);
        confirmOrderThroughCallback(confirmedOrderId);
        long missingOrderId = 999_999L;

        // act
        List<OrderOutboxBackfill.Outcome> outcomes = backfill.backfill(List.of(orderId, missingOrderId, confirmedOrderId));

        // assert
        assertThat(outcomes).extracting(OrderOutboxBackfill.Outcome::orderId, OrderOutboxBackfill.Outcome::result).containsExactly(
            tuple(orderId, OrderOutboxBackfill.Result.REJECTED),
            tuple(missingOrderId, OrderOutboxBackfill.Result.REJECTED),
            tuple(confirmedOrderId, OrderOutboxBackfill.Result.ADDED)
        );
        assertThat(outcomes.get(0).reason()).contains("확정 시각이 기록된 확정 주문만");
        assertThat(outcomes.get(1).reason()).contains("복구 대상 주문이 없습니다");
        assertThat(outboxRows()).as("미확정 주문에는 행을 만들지 않는다").isEmpty();
        assertThat(orderOutboxJpaRepository.findAllByOrderId(confirmedOrderId)).hasSize(1);
        assertThat(order().status()).isEqualTo(OrderStatus.DRAFT);
    }

    @DisplayName("같은 eventId의 기존 행이 저장된 확정 사실과 다르면 덮어쓰지 않고 거부한다.")
    @Test
    void rejectsConflictingExistingRow_withoutOverwriting() throws Exception {
        // arrange: W6 경로로 확정한 뒤, 판정 확인을 위해 같은 eventId에 다른 사실(다른 확정 시각·수량)의 행을 직접 넣는다
        confirmOrderThroughCallback(orderId);
        OrderConfirmedEvent conflicting = new OrderConfirmedEvent(
            OrderConfirmedEvent.EVENT_TYPE, OrderConfirmedEvent.SCHEMA_VERSION, OrderConfirmedEvent.eventIdOf(orderId), orderId,
            "2000-01-01T00:00:00Z", List.of(new OrderConfirmedEvent.Item(product.getId(), 99))
        );
        String conflictingPayload = orderEventSerializer.serialize(conflicting);
        orderOutboxJpaRepository.save(OrderOutboxModel.pending(conflicting, conflictingPayload, Instant.now()));

        // act
        List<OrderOutboxBackfill.Outcome> outcomes = backfill.backfill(List.of(orderId));

        // assert
        assertThat(outcomes).extracting(OrderOutboxBackfill.Outcome::result).containsExactly(OrderOutboxBackfill.Result.REJECTED);
        assertThat(outcomes.get(0).reason()).contains("저장된 확정 사실과 다릅니다");
        assertThat(onlyRow().getPayload()).as("기존 행을 고치지 않는다").isEqualTo(conflictingPayload);
    }
}
