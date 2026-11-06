package com.loopers.application.payment;

import com.loopers.domain.order.OrderConfirmedEvent;
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
 * 주문 확정 뒤 OrderConfirmed가 언제 발행되고 언제 발행되지 않는지 확인한다 (W6 과제 2절).
 * 실제 controller·MySQL·테스트 PG를 거치고, broker 자리에는 호출을 기록하는 발행기를 둔다.
 * 실제 broker로 보내는 동작은 {@code KafkaOrderEventPublisherTest}에서 확인한다.
 */
class OrderConfirmedPublicationTest extends PaymentScenarioTestBase {

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

        @DisplayName("저장된 확정 사실과 같은 이벤트를 한 번 발행한다.")
        @Test
        void publishesOnceWithStoredFacts() throws Exception {
            // arrange
            confirm().andExpect(status().isAccepted());
            pgCompletes(TestPgStatus.SUCCESS);

            // act
            callbackOf(pgTransaction()).andExpect(status().isOk()).andExpect(jsonPath("$.data.orderStatus").value("CONFIRMED"));

            // assert
            assertThat(publisher.events()).hasSize(1);
            OrderConfirmedEvent event = publisher.events().get(0);
            assertThat(event.eventType()).isEqualTo("OrderConfirmed");
            assertThat(event.schemaVersion()).isEqualTo(1);
            assertThat(event.eventId()).isEqualTo("order-confirmed:v1:" + orderId);
            assertThat(event.orderId()).isEqualTo(orderId);
            assertThat(event.key()).isEqualTo(String.valueOf(orderId));
            assertThat(event.confirmedAt()).isEqualTo(orderJpaRepository.findById(orderId).orElseThrow().getConfirmedAt().toString());
            assertThat(event.items()).containsExactly(new OrderConfirmedEvent.Item(product.getId(), ORDER_QUANTITY));
        }

        @DisplayName("조회가 먼저 확정해도 한 번만 발행하고, 뒤따르는 callback·조회는 새 이벤트를 만들지 않는다.")
        @Test
        void publishesOnce_whenLookupConfirmsFirstAndLaterApplicationsRepeat() throws Exception {
            // arrange
            confirm().andExpect(status().isAccepted());
            pgCompletes(TestPgStatus.SUCCESS);

            // act
            reconcile().andExpect(status().isOk());
            Instant confirmedAtBefore = orderJpaRepository.findById(orderId).orElseThrow().getConfirmedAt();
            callbackOf(pgTransaction()).andExpect(status().isOk());
            reconcile().andExpect(status().isOk());

            // assert
            assertThat(publisher.events()).hasSize(1);
            assertThat(orderJpaRepository.findById(orderId).orElseThrow().getConfirmedAt())
                .as("중복 callback·조회가 확정 시각을 바꾸지 못한다").isEqualTo(confirmedAtBefore);
            assertThat(stock()).as("추가 재고 차감 없음").isEqualTo(INITIAL_STOCK - ORDER_QUANTITY);
            assertThat(pg.createCount()).isEqualTo(1);
        }
    }

    @DisplayName("확정되지 않았거나 확정이 되돌려지면, ")
    @Nested
    class WhenNotConfirmed {

        @DisplayName("결과를 기다리는 동안에는 발행하지 않는다.")
        @Test
        void doesNotPublish_whilePending() throws Exception {
            // act
            confirm().andExpect(status().isAccepted());
            reconcile().andExpect(status().isAccepted());

            // assert
            assertThat(order().status()).isEqualTo(OrderStatus.PAYMENT_PENDING);
            assertThat(publisher.attempts()).isEmpty();
        }

        @DisplayName("PG가 확정 실패를 알리면 발행하지 않는다.")
        @Test
        void doesNotPublish_whenPaymentFails() throws Exception {
            // arrange
            confirm().andExpect(status().isAccepted());
            pgCompletes(TestPgStatus.FAILED);

            // act
            reconcile().andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PAYMENT_FAILED"));

            // assert
            assertThat(publisher.attempts()).isEmpty();
        }

        @DisplayName("확정 트랜잭션이 rollback되면 발행하지 않고, 다시 조회해 확정할 때 한 번 발행한다.")
        @Test
        void doesNotPublish_whenConfirmationTransactionRollsBack() throws Exception {
            // arrange
            confirm().andExpect(status().isAccepted());
            pgCompletes(TestPgStatus.SUCCESS);
            doThrow(new IllegalStateException("내부 저장 실패")).when(stockReservationRepository).findByOrderIdForUpdate(any());

            // act
            assertThatThrownBy(() -> paymentFacade.reconcile(orderId, USER_ID)).isInstanceOf(IllegalStateException.class);

            // assert
            assertThat(order().status()).isEqualTo(OrderStatus.PAYMENT_PENDING);
            assertThat(publisher.attempts()).as("commit되지 않은 확정은 발행하지 않는다").isEmpty();

            // act: 장애가 풀린 뒤 다시 조회한다
            Mockito.reset(stockReservationRepository);
            reconcile().andExpect(status().isOk());

            // assert
            assertThat(publisher.events()).hasSize(1);
        }
    }

    @DisplayName("commit 뒤 발행이 실패해도, ")
    @Nested
    class WhenPublicationFails {

        @DisplayName("확정된 주문·예약 소비는 그대로이고, 응답은 확정으로 답하며, PG를 다시 부르지 않는다. 이벤트는 broker에 없다.")
        @Test
        void keepsConfirmedOrderAndLeavesEventMissing() throws Exception {
            // arrange
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
            assertThat(publisher.attempts()).as("실패한 발행은 한 번만 시도한다. 자동 재발행은 W7의 몫이다").hasSize(1);
            assertThat(publisher.events()).as("broker가 받은 record는 없다").isEqualTo(List.of());
        }
    }
}
