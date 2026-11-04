package com.loopers.application.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.order.OrderOutboxModel;
import com.loopers.domain.order.OrderOutboxStatus;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.order.ReservationStatus;
import com.loopers.domain.order.StockReservationRepository;
import com.loopers.support.kafka.RecordingOrderEventPublisher;
import com.loopers.support.pg.TestPgStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 주문 확정에서 보낼 사실(OrderConfirmed)이 언제 Outbox에 남고 언제 남지 않는지 확인한다 (W7 과제 1·2절, design.md 3절).
 * 실제 controller·MySQL·테스트 PG를 거친다. 확정 경로는 broker를 부르지 않으므로, 발행기 자리의 기록기에는 호출이 없어야 한다.
 *
 * <p>W6의 {@code OrderConfirmedPublicationTest}가 "언제 발행하는가"로 확인하던 장면을 같은 순서로 옮겼다. 발행은 commit 뒤 relay의 일이 되었다.
 */
class OrderConfirmedOutboxTest extends PaymentScenarioTestBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private RecordingOrderEventPublisher publisher;

    @MockitoSpyBean
    private StockReservationRepository stockReservationRepository;

    @BeforeEach
    void clearPublisher() {
        publisher.clear();
    }

    @DisplayName("PG 성공이 처음 주문을 확정하면, ")
    @Nested
    class WhenFirstConfirmed {

        @DisplayName("저장된 확정 사실과 같은 Outbox 한 행을 PENDING으로 남기고, 확정 경로에서는 발행하지 않는다.")
        @Test
        void recordsOneOutboxRowWithStoredFacts() throws Exception {
            // arrange
            confirm().andExpect(status().isAccepted());
            pgCompletes(TestPgStatus.SUCCESS);

            // act
            callbackOf(pgTransaction()).andExpect(status().isOk()).andExpect(jsonPath("$.data.orderStatus").value("CONFIRMED"));

            // assert
            List<OrderOutboxModel> rows = outboxRows();
            assertThat(rows).hasSize(1);
            OrderOutboxModel row = rows.get(0);
            Instant confirmedAt = orderJpaRepository.findById(orderId).orElseThrow().getConfirmedAt();
            assertThat(row.getEventId()).isEqualTo("order-confirmed:v1:" + orderId);
            assertThat(row.getEventType()).isEqualTo("OrderConfirmed");
            assertThat(row.getSchemaVersion()).isEqualTo(1);
            assertThat(row.getMessageKey()).isEqualTo(String.valueOf(orderId));
            assertThat(row.getStatus()).isEqualTo(OrderOutboxStatus.PENDING);
            assertThat(row.getAttemptCount()).isZero();
            assertThat(row.getNextAttemptAt()).as("확정 시각부터 relay 대상").isEqualTo(confirmedAt);
            assertThat(row.getPublishedAt()).isNull();

            JsonNode payload = MAPPER.readTree(row.getPayload());
            assertThat(payload.get("eventType").asText()).isEqualTo("OrderConfirmed");
            assertThat(payload.get("schemaVersion").asInt()).isEqualTo(1);
            assertThat(payload.get("eventId").asText()).isEqualTo(row.getEventId());
            assertThat(payload.get("orderId").asLong()).isEqualTo(orderId);
            assertThat(payload.get("confirmedAt").asText()).isEqualTo(confirmedAt.toString());
            assertThat(payload.get("items")).hasSize(1);
            assertThat(payload.get("items").get(0).get("productId").asLong()).isEqualTo(product.getId());
            assertThat(payload.get("items").get(0).get("quantity").asInt()).isEqualTo(ORDER_QUANTITY);

            assertThat(publisher.attempts()).as("확정 경로는 broker로 보내지 않는다").isEmpty();
        }

        @DisplayName("조회가 먼저 확정해도 한 행만 남고, 뒤따르는 callback·조회는 행을 더하거나 payload를 바꾸지 않는다.")
        @Test
        void recordsOnce_whenLookupConfirmsFirstAndLaterApplicationsRepeat() throws Exception {
            // arrange
            confirm().andExpect(status().isAccepted());
            pgCompletes(TestPgStatus.SUCCESS);

            // act
            reconcile().andExpect(status().isOk());
            Instant confirmedAtBefore = orderJpaRepository.findById(orderId).orElseThrow().getConfirmedAt();
            String payloadBefore = outboxRows().get(0).getPayload();
            callbackOf(pgTransaction()).andExpect(status().isOk());
            reconcile().andExpect(status().isOk());

            // assert
            assertThat(outboxRows()).hasSize(1);
            assertThat(outboxRows().get(0).getPayload()).as("중복 적용이 저장한 사실을 바꾸지 못한다").isEqualTo(payloadBefore);
            assertThat(orderJpaRepository.findById(orderId).orElseThrow().getConfirmedAt())
                .as("중복 callback·조회가 확정 시각을 바꾸지 못한다").isEqualTo(confirmedAtBefore);
            assertThat(stock()).as("추가 재고 차감 없음").isEqualTo(INITIAL_STOCK - ORDER_QUANTITY);
            assertThat(pg.createCount()).isEqualTo(1);
            assertThat(publisher.attempts()).isEmpty();
        }
    }

    @DisplayName("확정되지 않았거나 확정이 되돌려지면, ")
    @Nested
    class WhenNotConfirmed {

        @DisplayName("결과를 기다리는 동안에는 Outbox를 남기지 않는다.")
        @Test
        void doesNotRecord_whilePending() throws Exception {
            // act
            confirm().andExpect(status().isAccepted());
            reconcile().andExpect(status().isAccepted());

            // assert
            assertThat(order().status()).isEqualTo(OrderStatus.PAYMENT_PENDING);
            assertThat(outboxRows()).isEmpty();
        }

        @DisplayName("PG가 확정 실패를 알리면 Outbox를 남기지 않는다.")
        @Test
        void doesNotRecord_whenPaymentFails() throws Exception {
            // arrange
            confirm().andExpect(status().isAccepted());
            pgCompletes(TestPgStatus.FAILED);

            // act
            reconcile().andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PAYMENT_FAILED"));

            // assert
            assertThat(outboxRows()).isEmpty();
        }

        @DisplayName("확정 트랜잭션이 rollback되면 Outbox도 남지 않고, 다시 조회해 확정할 때 한 행을 남긴다.")
        @Test
        void doesNotRecord_whenConfirmationTransactionRollsBack() throws Exception {
            // arrange
            confirm().andExpect(status().isAccepted());
            pgCompletes(TestPgStatus.SUCCESS);
            doThrow(new IllegalStateException("내부 저장 실패")).when(stockReservationRepository).findByOrderIdForUpdate(any());

            // act
            assertThatThrownBy(() -> paymentFacade.reconcile(orderId, USER_ID)).isInstanceOf(IllegalStateException.class);

            // assert
            assertThat(order().status()).isEqualTo(OrderStatus.PAYMENT_PENDING);
            assertThat(outboxRows()).as("commit되지 않은 확정은 보낼 사실도 남기지 않는다").isEmpty();

            // act: 장애가 풀린 뒤 다시 조회한다
            Mockito.reset(stockReservationRepository);
            reconcile().andExpect(status().isOk());

            // assert
            assertThat(outboxRows()).hasSize(1);
        }
    }

    @DisplayName("broker로 보낼 수 없는 상황이어도, ")
    @Nested
    class WhenBrokerUnavailable {

        @DisplayName("확정은 broker를 기다리지 않고 확정으로 답하며, 보낼 사실은 Outbox PENDING으로 남는다. PG를 다시 부르지 않는다.")
        @Test
        void confirmsWithoutTouchingBroker_andKeepsEventPending() throws Exception {
            // arrange: W6에서는 이 장면에서 commit 뒤 발행이 실패해 이벤트가 사라졌다
            publisher.failWith("broker 연결 실패(주입)");
            confirm().andExpect(status().isAccepted());
            pgCompletes(TestPgStatus.SUCCESS);

            // act
            callbackOf(pgTransaction()).andExpect(status().isOk()).andExpect(jsonPath("$.data.orderStatus").value("CONFIRMED"));
            reconcile().andExpect(status().isOk());

            // assert
            assertThat(order().status()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(reservation()).isEqualTo(ReservationStatus.CONSUMED);
            assertThat(stock()).isEqualTo(INITIAL_STOCK - ORDER_QUANTITY);
            assertThat(pg.createCount()).as("PG create를 다시 부르지 않는다").isEqualTo(1);
            assertThat(publisher.attempts()).as("확정 경로는 broker로 보내지 않는다. 발행은 relay의 일이다").isEmpty();
            assertThat(outboxRows()).extracting(OrderOutboxModel::getStatus).containsExactly(OrderOutboxStatus.PENDING);
        }
    }
}
