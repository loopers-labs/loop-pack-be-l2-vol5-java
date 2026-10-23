package com.loopers.application.payment;

import com.loopers.concurrency.ConcurrentRunner;
import com.loopers.concurrency.ConcurrentRunner.Outcome;
import com.loopers.concurrency.ConcurrentRunner.WorkerResult;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.order.ReservationStatus;
import com.loopers.domain.payment.PaymentAttemptStatus;
import com.loopers.domain.payment.PaymentConflict;
import com.loopers.support.pg.PaymentTestSupport;
import com.loopers.support.pg.TestPgStatus;
import com.loopers.support.pg.TestPgTransaction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * callback과 조회(reconcile)가 같은 결과 적용 경계로 모이는지 확인한다 (과제 5절, design.md 9-7절).
 * callback은 실제 controller를 거치고, 알림 본문의 성공 문자열만으로는 주문이 확정되지 않아야 한다.
 */
class PaymentCallbackIntegrationTest extends PaymentScenarioTestBase {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @DisplayName("callback과 조회의 순서가 바뀌어도, ")
    @Nested
    class ArrivalOrder {

        @DisplayName("callback이 먼저 와도 같은 최종 상태에 이르고, 나중 조회는 PG를 다시 부르지 않고 아무것도 바꾸지 않는다.")
        @Test
        void convergesToSameState_whenCallbackComesFirst() throws Exception {
            // arrange
            confirm().andExpect(status().isAccepted());
            pgCompletes(TestPgStatus.SUCCESS);

            // act
            callbackOf(pgTransaction()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderStatus").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.paymentStatus").value("SUCCESS"));
            reconcile().andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CONFIRMED"));

            // assert
            assertThat(order().status()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(attempt().getTransactionKey()).isEqualTo(pgTransaction().transactionKey());
            assertThat(reservation()).isEqualTo(ReservationStatus.CONSUMED);
            assertThat(stock()).isEqualTo(INITIAL_STOCK - ORDER_QUANTITY);
            assertThat(pg.createCount()).isEqualTo(1);
            assertThat(pg.lookupByKeyCount()).as("끝난 시도는 다시 조회하지 않는다").isEqualTo(1);
        }

        @DisplayName("조회가 먼저여도 같은 최종 상태에 이르고, 나중 callback은 재고·결제 효과를 더하지 않는다.")
        @Test
        void convergesToSameState_whenLookupComesFirst() throws Exception {
            // arrange
            confirm().andExpect(status().isAccepted());
            pgCompletes(TestPgStatus.SUCCESS);

            // act
            reconcile().andExpect(status().isOk());
            callbackOf(pgTransaction()).andExpect(status().isOk()).andExpect(jsonPath("$.data.orderStatus").value("CONFIRMED"));

            // assert
            assertThat(order().status()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(reservation()).isEqualTo(ReservationStatus.CONSUMED);
            assertThat(stock()).isEqualTo(INITIAL_STOCK - ORDER_QUANTITY);
            assertThat(pg.lookupByKeyCount()).isEqualTo(1);
        }

        @DisplayName("callback이 먼저 종결한 뒤 늦게 돌아온 생성 응답은, 종결 상태를 PENDING으로 되돌리지 않는다.")
        @Test
        void doesNotRevertTerminalState_whenCreateResponseArrivesLate() throws Exception {
            // arrange: 응답이 PG 쪽에서 붙잡힌 채, 그 사이 거래가 성공하고 callback이 먼저 도착한다
            pg.holdNextCreateResponse();
            Thread confirming = new Thread(() -> {
                try {
                    confirm();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            confirming.start();
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (pg.transactionCount() == 0 && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertThat(pg.transactionCount()).as("거래가 만들어진 뒤").isEqualTo(1);
            pg.complete(pg.transactionsOf(providerOrderId()).get(0).transactionKey(), TestPgStatus.SUCCESS);

            // act: 늦은 응답이 오기 전에 callback이 종결한다. 그 뒤 붙잡힌 응답이 풀린다
            callbackOf(pg.transactionsOf(providerOrderId()).get(0)).andExpect(status().isOk());
            pg.releaseHeld();
            confirming.join(Duration.ofSeconds(10).toMillis());

            // assert
            assertThat(order().status()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(attempt().getStatus()).isEqualTo(PaymentAttemptStatus.SUCCESS);
            assertThat(reservation()).isEqualTo(ReservationStatus.CONSUMED);
            assertThat(stock()).isEqualTo(INITIAL_STOCK - ORDER_QUANTITY);
        }

        @DisplayName("같은 성공 알림과 조회가 동시에 여러 번 들어와도, 한 번만 확정되고 재고·결제 효과가 중복되지 않는다.")
        @Test
        void appliesSuccessOnce_whenCallbacksAndReconcilesRunTogether() throws Exception {
            // arrange
            confirm().andExpect(status().isAccepted());
            pgCompletes(TestPgStatus.SUCCESS);
            TestPgTransaction transaction = pgTransaction();
            List<Callable<PaymentOutcome>> tasks = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                tasks.add(() -> paymentFacade.handleCallback(transaction.transactionKey(), transaction.orderId()));
                tasks.add(() -> paymentFacade.reconcile(orderId, USER_ID));
            }

            // act
            List<WorkerResult<PaymentOutcome>> results = ConcurrentRunner.runTogether(tasks, TIMEOUT);

            // assert
            assertThat(ConcurrentRunner.count(results, Outcome.SUCCESS)).isEqualTo(6);
            assertThat(results).extracting(WorkerResult::outcome).doesNotContain(Outcome.TECHNICAL_ERROR);
            assertThat(order().status()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(attempt().getStatus()).isEqualTo(PaymentAttemptStatus.SUCCESS);
            assertThat(attempt().getConflict()).isNull();
            assertThat(reservation()).isEqualTo(ReservationStatus.CONSUMED);
            assertThat(stock()).isEqualTo(INITIAL_STOCK - ORDER_QUANTITY);
            assertThat(balance()).isEqualTo(INITIAL_BALANCE);
        }

        @DisplayName("같은 실패 알림이 동시에 여러 번 들어와도, 예약은 한 번만 반환되어 가용 재고가 5를 넘지 않는다.")
        @Test
        void returnsReservationOnce_whenFailureCallbacksRunTogether() throws Exception {
            // arrange
            confirm().andExpect(status().isAccepted());
            pgCompletes(TestPgStatus.FAILED);
            TestPgTransaction transaction = pgTransaction();
            List<Callable<PaymentOutcome>> tasks = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                tasks.add(() -> paymentFacade.handleCallback(transaction.transactionKey(), transaction.orderId()));
            }

            // act
            List<WorkerResult<PaymentOutcome>> results = ConcurrentRunner.runTogether(tasks, TIMEOUT);

            // assert
            assertThat(results).extracting(WorkerResult::outcome).containsOnly(Outcome.SUCCESS);
            assertThat(order().status()).isEqualTo(OrderStatus.PAYMENT_FAILED);
            assertThat(reservation()).isEqualTo(ReservationStatus.RELEASED);
            assertThat(stock()).as("4번 반환했다면 13").isEqualTo(INITIAL_STOCK);
        }
    }

    @DisplayName("알림 본문의 PG 주문 번호 이름이 orderId이든 providerOrderId이든, ")
    @Nested
    class FieldNames {

        @DisplayName("providerOrderId로 보낸 알림도 같은 거래를 조회해 같은 결과로 확정한다.")
        @Test
        void appliesSameResult_whenBodyUsesProviderOrderIdName() throws Exception {
            // arrange
            confirm().andExpect(status().isAccepted());
            pgCompletes(TestPgStatus.SUCCESS);
            TestPgTransaction transaction = pgTransaction();

            // act
            callbackWithProviderOrderIdName(transaction.transactionKey(), transaction.orderId(), "SUCCESS")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderStatus").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.paymentStatus").value("SUCCESS"));

            // assert
            assertThat(order().status()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(reservation()).isEqualTo(ReservationStatus.CONSUMED);
            assertThat(stock()).isEqualTo(INITIAL_STOCK - ORDER_QUANTITY);
        }

        @DisplayName("providerOrderId로 보낸 알림이어도 본문을 믿지 않는다. PG가 PENDING이면 SUCCESS라고 적혀 있어도 확정하지 않는다.")
        @Test
        void stillDoesNotTrustBody_whenBodyUsesProviderOrderIdName() throws Exception {
            // arrange
            confirm().andExpect(status().isAccepted());
            TestPgTransaction transaction = pgTransaction();

            // act
            callbackWithProviderOrderIdName(transaction.transactionKey(), transaction.orderId(), "SUCCESS")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.orderStatus").value("PAYMENT_PENDING"));

            // assert
            assertThat(order().status()).isEqualTo(OrderStatus.PAYMENT_PENDING);
            assertThat(reservation()).isEqualTo(ReservationStatus.RESERVED);
        }

        @DisplayName("두 이름이 모두 없으면 400이고 아무것도 바뀌지 않는다.")
        @Test
        void rejects_whenNeitherNameIsPresent() throws Exception {
            // arrange
            confirm().andExpect(status().isAccepted());

            // act & assert
            mvc.perform(post("/api/v1/payments/callback")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"transactionKey\":\"TEST-TX-0001\",\"status\":\"SUCCESS\"}"))
                .andExpect(status().isBadRequest());
            assertThat(order().status()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        }
    }

    @DisplayName("알림 본문을 믿지 않아서, ")
    @Nested
    class DoesNotTrustCallbackBody {

        @DisplayName("PG는 아직 PENDING인데 본문이 SUCCESS인 조작된 알림으로는 주문이 확정되지 않는다.")
        @Test
        void doesNotConfirm_whenBodyClaimsSuccessButPgIsPending() throws Exception {
            // arrange
            confirm().andExpect(status().isAccepted());
            TestPgTransaction transaction = pgTransaction();

            // act
            callback(transaction.transactionKey(), transaction.orderId(), "SUCCESS")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.orderStatus").value("PAYMENT_PENDING"))
                .andExpect(jsonPath("$.data.paymentStatus").value("PENDING"));

            // assert
            assertThat(order().status()).isEqualTo(OrderStatus.PAYMENT_PENDING);
            assertThat(order().paidAmount()).isNull();
            assertThat(reservation()).isEqualTo(ReservationStatus.RESERVED);
            assertThat(stock()).isEqualTo(INITIAL_STOCK - ORDER_QUANTITY);
        }

        @DisplayName("PG는 SUCCESS인데 본문이 FAILED인 알림이어도, 본문이 아니라 PG의 조회 결과대로 확정한다.")
        @Test
        void followsPgLookup_whenBodyClaimsFailure() throws Exception {
            // arrange
            confirm().andExpect(status().isAccepted());
            pgCompletes(TestPgStatus.SUCCESS);
            TestPgTransaction transaction = pgTransaction();

            // act
            callback(transaction.transactionKey(), transaction.orderId(), "FAILED").andExpect(status().isOk());

            // assert
            assertThat(order().status()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(stock()).isEqualTo(INITIAL_STOCK - ORDER_QUANTITY);
        }

        @DisplayName("알 수 없는 PG 주문 번호는 404이고, 거래 키·주문 번호가 비어 있으면 400이며, 아무것도 바뀌지 않는다.")
        @Test
        void rejectsUnknownOrBlankIdentifiers() throws Exception {
            // arrange
            confirm().andExpect(status().isAccepted());

            // act & assert
            callback("TEST-TX-0001", "LP-ORD-0000000999", "SUCCESS").andExpect(status().isNotFound());
            callback(" ", providerOrderId(), "SUCCESS").andExpect(status().isBadRequest());
            callback("TEST-TX-0001", "", "SUCCESS").andExpect(status().isBadRequest());
            assertThat(order().status()).isEqualTo(OrderStatus.PAYMENT_PENDING);
            assertThat(stock()).isEqualTo(INITIAL_STOCK - ORDER_QUANTITY);
        }

        @DisplayName("끝난 주문에는 이후 상충하는 알림이 와도 결과를 덮어쓰지 않는다.")
        @Test
        void doesNotOverwrite_whenConflictingCallbackArrivesAfterTermination() throws Exception {
            // arrange: 성공으로 확정된 뒤 PG 쪽 거래가 다른 값으로 바뀌었다고 알려 온다
            confirm().andExpect(status().isAccepted());
            pgCompletes(TestPgStatus.SUCCESS);
            reconcile().andExpect(status().isOk());
            TestPgTransaction transaction = pgTransaction();
            pg.complete(transaction.transactionKey(), TestPgStatus.FAILED);

            // act
            callback(transaction.transactionKey(), transaction.orderId(), "FAILED").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderStatus").value("CONFIRMED"));

            // assert
            assertThat(order().status()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(attempt().getStatus()).isEqualTo(PaymentAttemptStatus.SUCCESS);
            assertThat(reservation()).isEqualTo(ReservationStatus.CONSUMED);
            assertThat(stock()).isEqualTo(INITIAL_STOCK - ORDER_QUANTITY);
        }
    }

    @DisplayName("조회 결과가 모호하거나 이 시도의 것이 아니면, ")
    @Nested
    class AmbiguousLookup {

        @DisplayName("다른 주문의 거래 키를 담은 알림은 충돌로 기록하고, 그 거래가 SUCCESS여도 주문·재고를 바꾸지 않는다.")
        @Test
        void recordsOrderMismatch_whenCallbackPointsToAnotherOrdersTransaction() throws Exception {
            // arrange
            confirm().andExpect(status().isAccepted());
            TestPgTransaction others = seedOtherTransaction("LP-ORD-0000000999", ORDER_AMOUNT, TestPgStatus.SUCCESS);

            // act
            callback(others.transactionKey(), providerOrderId(), "SUCCESS").andExpect(status().isAccepted());

            // assert
            assertThat(order().status()).isEqualTo(OrderStatus.PAYMENT_PENDING);
            assertThat(order().payment().conflict()).isEqualTo(PaymentConflict.ORDER_MISMATCH);
            assertThat(reservation()).isEqualTo(ReservationStatus.RESERVED);
            assertThat(stock()).isEqualTo(INITIAL_STOCK - ORDER_QUANTITY);
        }

        @DisplayName("금액이 저장된 금액과 다른 거래는 SUCCESS여도 확정하지 않고 충돌로 기록한다.")
        @Test
        void recordsAmountMismatch_whenTransactionAmountDiffers() throws Exception {
            // arrange
            confirm().andExpect(status().isAccepted());
            TestPgTransaction wrongAmount = seedOtherTransaction(providerOrderId(), ORDER_AMOUNT + 1, TestPgStatus.SUCCESS);

            // act
            callback(wrongAmount.transactionKey(), providerOrderId(), "SUCCESS").andExpect(status().isAccepted());

            // assert
            assertThat(order().status()).isEqualTo(OrderStatus.PAYMENT_PENDING);
            assertThat(order().paidAmount()).isNull();
            assertThat(order().payment().conflict()).isEqualTo(PaymentConflict.AMOUNT_MISMATCH);
            assertThat(stock()).isEqualTo(INITIAL_STOCK - ORDER_QUANTITY);
        }

        @DisplayName("조회가 0건이면 임의로 실패·재결제하지 않는다. 결과 미확인을 그대로 두고 create를 다시 보내지 않는다.")
        @Test
        void keepsPending_whenLookupFindsNothing() throws Exception {
            // arrange: 발송 기록(TX A)만 남고 실제 발송이 없었다
            paymentTransactions.start(orderId, USER_ID, PaymentTestSupport.attemptKeyOf(orderId), PaymentTestSupport.CARD_TYPE);

            // act
            reconcile().andExpect(status().isAccepted()).andExpect(jsonPath("$.data.status").value("PAYMENT_PENDING"));
            reconcile().andExpect(status().isAccepted());

            // assert
            assertThat(attempt().getStatus()).isEqualTo(PaymentAttemptStatus.REQUESTED);
            assertThat(attempt().getConflict()).isNull();
            assertThat(reservation()).isEqualTo(ReservationStatus.RESERVED);
            assertThat(stock()).isEqualTo(INITIAL_STOCK - ORDER_QUANTITY);
            assertThat(pg.createCount()).as("자동 재발송 0").isZero();
            assertThat(pg.transactionCount()).isZero();
        }

        @DisplayName("같은 주문의 거래가 2건 이상이면 첫 거래를 고르지 않고 충돌로 남기며, 둘 다 SUCCESS여도 확정하지 않는다.")
        @Test
        void recordsConflict_whenMultipleTransactionsExist() throws Exception {
            // arrange: 응답을 잃어 아직 거래 키를 모르고, PG에는 같은 주문의 거래가 둘 있다
            pg.dropNextCreateResponse();
            confirm().andExpect(status().isAccepted());
            seedOtherTransaction(providerOrderId(), ORDER_AMOUNT, TestPgStatus.PENDING);
            pg.transactionsOf(providerOrderId()).forEach(transaction -> pg.complete(transaction.transactionKey(), TestPgStatus.SUCCESS));

            // act
            reconcile().andExpect(status().isAccepted()).andExpect(jsonPath("$.data.status").value("PAYMENT_PENDING"));

            // assert
            assertThat(pg.transactionsOf(providerOrderId())).hasSize(2);
            assertThat(attempt().getStatus()).isEqualTo(PaymentAttemptStatus.UNKNOWN);
            assertThat(attempt().getTransactionKey()).isNull();
            assertThat(attempt().getConflict()).isEqualTo(PaymentConflict.MULTIPLE_TRANSACTIONS);
            assertThat(reservation()).isEqualTo(ReservationStatus.RESERVED);
            assertThat(stock()).isEqualTo(INITIAL_STOCK - ORDER_QUANTITY);
            assertThat(pg.createCount()).isEqualTo(1);
        }
    }
}
