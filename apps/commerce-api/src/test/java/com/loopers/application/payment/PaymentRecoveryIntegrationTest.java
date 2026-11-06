package com.loopers.application.payment;

import com.loopers.domain.order.OrderEventPublisher;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.order.ReservationStatus;
import com.loopers.domain.order.StockReservationRepository;
import com.loopers.domain.payment.PaymentAttemptStatus;
import com.loopers.domain.payment.PgCreateResult;
import com.loopers.domain.payment.PgPaymentRequest;
import com.loopers.domain.product.ProductRevisionRepository;
import com.loopers.infrastructure.payment.PgProperties;
import com.loopers.infrastructure.payment.TestPgPaymentGateway;
import com.loopers.support.pg.PaymentTestSupport;
import com.loopers.support.pg.TestPgLookupFailure;
import com.loopers.support.pg.TestPgStatus;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.Session;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 내부 실패·재시작·PG 조회 장애에서 결제 결과를 지어내지 않고 한 번만 반영하는지 확인한다 (과제 4·5절, design.md 9-4절).
 * 외부 거래는 우리 DB rollback으로 되돌릴 수 없으므로, 내부 저장이 실패해도 같은 거래를 다시 조회해 한 번 확정할 수 있어야 한다.
 */
class PaymentRecoveryIntegrationTest extends PaymentScenarioTestBase {

    @PersistenceContext
    private EntityManager entityManager;

    @MockitoSpyBean
    private StockReservationRepository stockReservationRepository;

    @MockitoSpyBean
    private ProductRevisionRepository productRevisionRepository;

    @Autowired
    private PgProperties pgProperties;

    @Autowired
    private OrderEventPublisher orderEventPublisher;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @DisplayName("외부 성공 뒤 내부 저장이 실패하면, ")
    @Nested
    class InternalFailureAfterExternalResult {

        @DisplayName("성공 적용이 rollback돼 상태가 그대로이고, 같은 거래를 다시 조회해 한 번 확정한다. create는 더 보내지 않는다.")
        @Test
        void settlesOnceByLookingUpAgain_afterSuccessApplicationFails() throws Exception {
            // arrange
            confirm().andExpect(status().isAccepted());
            pgCompletes(TestPgStatus.SUCCESS);
            doThrow(new IllegalStateException("내부 저장 실패")).when(stockReservationRepository).findByOrderIdForUpdate(any());

            // act: 결과 적용(TX B)이 실패한다
            assertThatThrownBy(() -> paymentFacade.reconcile(orderId, USER_ID)).isInstanceOf(IllegalStateException.class);

            // assert: PG는 성공이지만 우리 쪽은 아직 아무것도 바뀌지 않았다
            assertThat(order().status()).isEqualTo(OrderStatus.PAYMENT_PENDING);
            assertThat(attempt().getStatus()).isEqualTo(PaymentAttemptStatus.PENDING);
            assertThat(reservation()).isEqualTo(ReservationStatus.RESERVED);
            assertThat(stock()).isEqualTo(INITIAL_STOCK - ORDER_QUANTITY);

            // act: 장애가 풀린 뒤 같은 거래를 다시 조회한다
            Mockito.reset(stockReservationRepository);
            reconcile().andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CONFIRMED"));
            reconcile().andExpect(status().isOk());

            // assert
            assertThat(order().status()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(reservation()).isEqualTo(ReservationStatus.CONSUMED);
            assertThat(stock()).isEqualTo(INITIAL_STOCK - ORDER_QUANTITY);
            assertThat(pg.createCount()).as("create 추가 0").isEqualTo(1);
            assertThat(pg.transactionCount()).isEqualTo(1);
        }

        @DisplayName("재고 반환이 DB로 나간 뒤 실패해도 반환·주문 실패·시도 종결이 모두 rollback되고, 다시 조회하면 한 번만 반환한다.")
        @Test
        void returnsStockOnce_afterFailureApplicationIsRolledBackAndRetried() throws Exception {
            // arrange
            confirm().andExpect(status().isAccepted());
            pgCompletes(TestPgStatus.FAILED);
            AtomicBoolean hadPendingChanges = new AtomicBoolean(false);
            doAnswer(invocation -> {
                hadPendingChanges.set(entityManager.unwrap(Session.class).isDirty());
                entityManager.flush();
                throw new IllegalStateException("상품 revision 저장 실패");
            }).when(productRevisionRepository).increase(any());

            // act: 반환 도중(재고·예약·시도·주문 변경이 DB로 나간 뒤) 실패한다
            assertThatThrownBy(() -> paymentFacade.reconcile(orderId, USER_ID)).isInstanceOf(IllegalStateException.class);

            // assert: 실패 시점에 변경이 실제로 나갔고, 요청이 끝난 뒤에는 전부 원래대로다
            assertThat(hadPendingChanges).isTrue();
            assertThat(stock()).isEqualTo(INITIAL_STOCK - ORDER_QUANTITY);
            assertThat(order().status()).isEqualTo(OrderStatus.PAYMENT_PENDING);
            assertThat(attempt().getStatus()).isEqualTo(PaymentAttemptStatus.PENDING);
            assertThat(reservation()).isEqualTo(ReservationStatus.RESERVED);

            // act
            Mockito.reset(productRevisionRepository);
            reconcile().andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PAYMENT_FAILED"));
            reconcile().andExpect(status().isOk());

            // assert
            assertThat(stock()).as("반환은 정확히 한 번").isEqualTo(INITIAL_STOCK);
            assertThat(reservation()).isEqualTo(ReservationStatus.RELEASED);
            assertThat(order().status()).isEqualTo(OrderStatus.PAYMENT_FAILED);
            assertThat(pg.createCount()).isEqualTo(1);
        }
    }

    @DisplayName("발송을 기록한 뒤 프로세스가 멈춰 재시작하면, ")
    @Nested
    class AfterRestart {

        // 재시작: 서비스·클라이언트를 새로 만들되 같은 DB와 같은 PG(외부 거래) 상태를 쓴다
        private PaymentFacade restartedFacade() {
            TestPgPaymentGateway newClient = new TestPgPaymentGateway(pgProperties, CircuitBreakerRegistry.ofDefaults());
            return new PaymentFacade(paymentTransactions, newClient, orderEventPublisher);
        }

        @DisplayName("실제 발송이 없었어도 저장된 시도·예약이 남고, 같은 요청도 조회도 create를 자동으로 다시 보내지 않는다.")
        @Test
        void neverResends_whenSendWasRecordedButNothingWasSent() {
            // arrange: TX A는 commit됐지만 HTTP를 보내기 전에 멈췄다
            String key = PaymentTestSupport.attemptKeyOf(orderId);
            paymentTransactions.start(orderId, USER_ID, key, PaymentTestSupport.CARD_TYPE);
            PaymentFacade restarted = restartedFacade();

            // act
            PaymentOutcome repeated = restarted.confirm(orderId, USER_ID, key, PaymentTestSupport.CARD_TYPE, PaymentTestSupport.CARD_NO);
            PaymentOutcome reconciled = restarted.reconcile(orderId, USER_ID);

            // assert
            assertThat(repeated.settled()).isFalse();
            assertThat(reconciled.settled()).isFalse();
            assertThat(attempt().getStatus()).isEqualTo(PaymentAttemptStatus.REQUESTED);
            assertThat(reservation()).isEqualTo(ReservationStatus.RESERVED);
            assertThat(stock()).isEqualTo(INITIAL_STOCK - ORDER_QUANTITY);
            assertThat(pg.createCount()).as("미확인 시도 자동 재발송 0").isZero();
            assertThat(pg.transactionCount()).isZero();
        }

        @DisplayName("발송과 응답이 있었지만 결과 적용 전에 멈췄어도, 기존 거래를 조회해 한 번 확정하고 create를 더 보내지 않는다.")
        @Test
        void settlesByLookup_whenSendHappenedButResultWasNeverApplied() {
            // arrange: TX A → 발송 → (TX B 전에 멈춤)
            PaymentTransactions.Started started = paymentTransactions.start(
                orderId, USER_ID, PaymentTestSupport.attemptKeyOf(orderId), PaymentTestSupport.CARD_TYPE);
            PgCreateResult sent = new TestPgPaymentGateway(pgProperties, CircuitBreakerRegistry.ofDefaults()).requestPayment(
                new PgPaymentRequest(started.providerOrderId(), PaymentTestSupport.CARD_TYPE, PaymentTestSupport.CARD_NO, started.amount()));
            assertThat(sent).isInstanceOf(PgCreateResult.Accepted.class);
            assertThat(attempt().getStatus()).as("결과를 적용하지 못했다").isEqualTo(PaymentAttemptStatus.REQUESTED);
            PaymentFacade restarted = restartedFacade();

            // act
            PaymentOutcome whilePending = restarted.reconcile(orderId, USER_ID);
            pgCompletes(TestPgStatus.SUCCESS);
            PaymentOutcome afterSuccess = restarted.reconcile(orderId, USER_ID);

            // assert
            assertThat(whilePending.settled()).isFalse();
            assertThat(afterSuccess.settled()).isTrue();
            assertThat(order().status()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(attempt().getTransactionKey()).isEqualTo(pgTransaction().transactionKey());
            assertThat(reservation()).isEqualTo(ReservationStatus.CONSUMED);
            assertThat(stock()).isEqualTo(INITIAL_STOCK - ORDER_QUANTITY);
            assertThat(pg.createCount()).isEqualTo(1);
            assertThat(pg.transactionCount()).isEqualTo(1);
        }
    }

    @DisplayName("PG 조회가 실패하거나 회로가 열리면, ")
    @Nested
    class LookupOutage {

        private CircuitBreaker lookupCircuit() {
            return circuitBreakerRegistry.circuitBreaker(TestPgPaymentGateway.LOOKUP_CIRCUIT_BREAKER);
        }

        @DisplayName("대기·호출 수가 제한되고 저장된 상태에 확인 불가만 표시한다. 결제를 실패로 정하지 않고, 회복되면 조회를 다시 시작한다.")
        @Test
        void keepsStoredState_limitsCalls_andResumesAfterRecovery() throws Exception {
            // arrange
            confirm().andExpect(status().isAccepted());
            pg.failLookups(TestPgLookupFailure.SERVER_ERROR);

            // act & assert: 첫 조회는 재시도 3번, 둘째는 표본이 다 차서 회로가 열린다
            reconcile().andExpect(status().isAccepted()).andExpect(jsonPath("$.data.payment.lookupUnavailable").value(true));
            assertThat(pg.lookupByKeyCount()).isEqualTo(3);
            reconcile().andExpect(status().isAccepted()).andExpect(jsonPath("$.data.payment.lookupUnavailable").value(true));
            assertThat(pg.lookupByKeyCount()).isEqualTo(5);
            assertThat(lookupCircuit().getState()).isEqualTo(CircuitBreaker.State.OPEN);

            // act & assert: 회로가 열려 있는 동안은 PG에 묻지 않지만 결제를 실패로 확정하지도 않는다
            reconcile().andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("PAYMENT_PENDING"))
                .andExpect(jsonPath("$.data.payment.status").value("PENDING"))
                .andExpect(jsonPath("$.data.payment.lookupUnavailable").value(true));
            assertThat(pg.lookupByKeyCount()).as("차단된 동안 호출 수 제한").isEqualTo(5);
            assertThat(order().status()).isEqualTo(OrderStatus.PAYMENT_PENDING);
            assertThat(attempt().getStatus()).isEqualTo(PaymentAttemptStatus.PENDING);
            assertThat(reservation()).isEqualTo(ReservationStatus.RESERVED);
            assertThat(stock()).isEqualTo(INITIAL_STOCK - ORDER_QUANTITY);
            assertThat(pg.createCount()).isEqualTo(1);

            // act: PG가 회복되고 대기 시간이 지나 탐색 호출이 허용된다 (OPEN 대기 시간 자체를 기다리지 않고 전환한다)
            pg.healLookups();
            lookupCircuit().transitionToHalfOpenState();
            reconcile().andExpect(status().isAccepted()).andExpect(jsonPath("$.data.payment.lookupUnavailable").doesNotExist());
            reconcile().andExpect(status().isAccepted());
            assertThat(lookupCircuit().getState()).isEqualTo(CircuitBreaker.State.CLOSED);

            // assert: 회복 뒤 조회가 다시 되어 성공을 한 번 확정한다
            pgCompletes(TestPgStatus.SUCCESS);
            reconcile().andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CONFIRMED"));
            assertThat(reservation()).isEqualTo(ReservationStatus.CONSUMED);
            assertThat(stock()).isEqualTo(INITIAL_STOCK - ORDER_QUANTITY);
            assertThat(pg.createCount()).isEqualTo(1);
        }

        @DisplayName("PG가 응답하지 못해도 callback은 상태를 바꾸지 않고, 확인 불가를 알린다.")
        @Test
        void callbackKeepsState_whenLookupFails() throws Exception {
            // arrange
            confirm().andExpect(status().isAccepted());
            var transaction = pgTransaction();
            pg.failLookups(TestPgLookupFailure.INVALID_JSON);

            // act
            callback(transaction.transactionKey(), transaction.orderId(), "SUCCESS")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.orderStatus").value("PAYMENT_PENDING"));

            // assert
            assertThat(order().status()).isEqualTo(OrderStatus.PAYMENT_PENDING);
            assertThat(reservation()).isEqualTo(ReservationStatus.RESERVED);
            assertThat(stock()).isEqualTo(INITIAL_STOCK - ORDER_QUANTITY);
        }
    }
}
