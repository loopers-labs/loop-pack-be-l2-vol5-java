package com.loopers.application.payment;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderRepository;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.order.PaymentMethod;
import com.loopers.domain.order.PaymentResult;
import com.loopers.domain.payment.PaymentAttempt;
import com.loopers.domain.payment.PaymentAttemptRepository;
import com.loopers.domain.payment.PaymentAttemptStatus;
import com.loopers.domain.payment.PaymentConflictReason;
import com.loopers.domain.point.Point;
import com.loopers.domain.point.PointRepository;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.support.testpg.TestPgControl;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;

// 결과 반영(TX B)을 실제 MySQL과 실제 adapter·로컬 테스트 PG로 확인한다.
// TX A의 재고 10에서 수량 2를 확보한 PAYMENT_PENDING 주문과 REQUESTED 시도를 준비해 시작한다.
@SpringBootTest
class PaymentResultServiceIntegrationTest {

    private static final long USER_ID = 1L;
    private static final long INITIAL_STOCK = 10L;
    private static final int QUANTITY = 2;
    private static final long PRICE = 3_500L;

    @Autowired
    private PaymentResultService paymentResultService;

    @Autowired
    private PaymentGateway paymentGateway;

    @Autowired
    private PaymentAttemptRepository paymentAttemptRepository;

    @MockitoSpyBean
    private OrderRepository orderRepository;

    @MockitoSpyBean
    private ProductRepository productRepository;

    @Autowired
    private BrandRepository brandRepository;

    @Autowired
    private PointRepository pointRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @Nested
    class CreateResponse {

        @DisplayName("생성 응답 ACCEPTED는 거래 키와 PENDING을 기록하고 주문·재고를 유지한다.")
        @Test
        void recordsAcceptedAsPending() {
            Fixture fixture = fixture(1);

            PaymentReflection reflection = paymentResultService.applyCreateResponse(
                fixture.attemptId(), new PaymentCreateObservation.Accepted("TX-1"));

            assertThat(reflection.outcome()).isEqualTo(PaymentReflectionOutcome.APPLIED);
            assertThat(reflection.payment().attemptStatus()).isEqualTo(PaymentAttemptStatus.PENDING);
            assertThat(reflection.payment().transactionKey()).isEqualTo("TX-1");
            assertThat(reflection.payment().orderStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
            assertThat(stocks(fixture)).containsExactly(8L);
        }

        @DisplayName("생성 응답 UNKNOWN은 거래 키 없이 UNKNOWN을 기록하고 주문·재고를 유지한다.")
        @Test
        void recordsUnknownCreateResponse() {
            Fixture fixture = fixture(1);

            PaymentReflection reflection = paymentResultService.applyCreateResponse(
                fixture.attemptId(), new PaymentCreateObservation.Unknown("timeout"));

            assertThat(reflection.outcome()).isEqualTo(PaymentReflectionOutcome.APPLIED);
            assertThat(attempt(fixture).getStatus()).isEqualTo(PaymentAttemptStatus.UNKNOWN);
            assertThat(attempt(fixture).getTransactionKey()).isNull();
            assertThat(order(fixture).getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
            assertThat(stocks(fixture)).containsExactly(8L);
        }

        @DisplayName("생성 전 확정 거절은 거래 키 없이 실패로 종결하고, 같은 거절을 다시 적용해도 재고는 한 번만 반환한다.")
        @Test
        void failsRejectedAttemptAndReturnsStockOnce() {
            Fixture fixture = fixture(2);

            PaymentReflection first = paymentResultService.applyCreateResponse(
                fixture.attemptId(), new PaymentCreateObservation.RejectedNoEffect());
            PaymentReflection duplicate = paymentResultService.applyCreateResponse(
                fixture.attemptId(), new PaymentCreateObservation.RejectedNoEffect());

            assertThat(first.outcome()).isEqualTo(PaymentReflectionOutcome.APPLIED);
            assertThat(duplicate.outcome()).isEqualTo(PaymentReflectionOutcome.UNCHANGED);
            assertThat(attempt(fixture).getStatus()).isEqualTo(PaymentAttemptStatus.FAILED);
            assertThat(attempt(fixture).getTransactionKey()).isNull();
            assertThat(attempt(fixture).getConflictReason()).isNull();
            Order order = order(fixture);
            assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_FAILED);
            assertThat(order.getPaymentMethod()).isEqualTo(PaymentMethod.PG);
            assertThat(order.getPaymentResult()).isEqualTo(PaymentResult.FAILED);
            assertThat(stocks(fixture)).containsExactly(INITIAL_STOCK, INITIAL_STOCK);
        }

        @DisplayName("종결 뒤 늦게 도착한 생성 응답은 상태와 거래 키를 바꾸지 않는다.")
        @Test
        void ignoresLateCreateResponseAfterTermination() {
            Fixture fixture = fixture(1);
            paymentResultService.applyLookup(fixture.attemptId(), found(fixture, "TX-1", PgTransactionStatus.SUCCESS));

            PaymentReflection lateAccepted = paymentResultService.applyCreateResponse(
                fixture.attemptId(), new PaymentCreateObservation.Accepted("TX-1"));
            PaymentReflection lateUnknown = paymentResultService.applyCreateResponse(
                fixture.attemptId(), new PaymentCreateObservation.Unknown("timeout"));

            assertThat(lateAccepted.outcome()).isEqualTo(PaymentReflectionOutcome.UNCHANGED);
            assertThat(lateUnknown.outcome()).isEqualTo(PaymentReflectionOutcome.UNCHANGED);
            assertThat(attempt(fixture).getStatus()).isEqualTo(PaymentAttemptStatus.SUCCEEDED);
            assertThat(attempt(fixture).getTransactionKey()).isEqualTo("TX-1");
            assertThat(attempt(fixture).getConflictReason()).isNull();
        }
    }

    @Nested
    class LookupWithTestPg {

        @DisplayName("PG에서 성공을 확인하면 시도와 주문을 확정하고 결제액·결제수단을 저장하며 재고는 차감 상태를 유지한다.")
        @Test
        void confirmsSuccessfulTransaction() {
            Fixture fixture = fixture(1);
            String transactionKey = acceptInPg(fixture);
            TestPgControl.complete(fixture.providerOrderId());

            PaymentReflection reflection = paymentResultService.check(fixture.attemptId());

            assertThat(reflection.outcome()).isEqualTo(PaymentReflectionOutcome.APPLIED);
            assertThat(reflection.payment().attemptStatus()).isEqualTo(PaymentAttemptStatus.SUCCEEDED);
            assertThat(reflection.payment().orderStatus()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(attempt(fixture).getTransactionKey()).isEqualTo(transactionKey);
            Order order = order(fixture);
            assertThat(order.getPaymentMethod()).isEqualTo(PaymentMethod.PG);
            assertThat(order.getPaymentAmount()).isEqualTo(fixture.amount());
            assertThat(order.getPaymentResult()).isEqualTo(PaymentResult.SUCCESS);
            assertThat(order.getItems()).singleElement().extracting(OrderItem::getQuantity).isEqualTo(QUANTITY);
            assertThat(stocks(fixture)).containsExactly(8L);
            assertThat(TestPgControl.stats(fixture.providerOrderId()).transactions()).isEqualTo(1);
        }

        @DisplayName("PG에서 실패를 확인하면 주문을 실패로 종결하고 모든 품목의 재고를 반환하며, 이후 확인은 PG를 조회하지 않는다.")
        @Test
        void failsTransactionAndReturnsEveryItem() {
            Fixture fixture = fixture(2);
            TestPgControl.scenario(fixture.providerOrderId(), "{\"scenario\":\"FAIL\"}");
            acceptInPg(fixture);
            TestPgControl.complete(fixture.providerOrderId());

            PaymentReflection reflection = paymentResultService.check(fixture.attemptId());
            int lookupsAfterFailure = TestPgControl.stats(fixture.providerOrderId()).lookupRequests();
            PaymentReflection again = paymentResultService.check(fixture.attemptId());

            assertThat(reflection.outcome()).isEqualTo(PaymentReflectionOutcome.APPLIED);
            assertThat(reflection.payment().attemptStatus()).isEqualTo(PaymentAttemptStatus.FAILED);
            assertThat(reflection.payment().orderStatus()).isEqualTo(OrderStatus.PAYMENT_FAILED);
            assertThat(again.outcome()).isEqualTo(PaymentReflectionOutcome.UNCHANGED);
            assertThat(TestPgControl.stats(fixture.providerOrderId()).lookupRequests()).isEqualTo(lookupsAfterFailure);
            assertThat(order(fixture).getItems()).extracting(OrderItem::getQuantity).containsExactly(QUANTITY, QUANTITY);
            assertThat(stocks(fixture)).containsExactly(INITIAL_STOCK, INITIAL_STOCK);
        }

        @DisplayName("생성 응답을 잃은 시도는 UNKNOWN으로 남고, PG 주문 식별자로 기존 거래를 찾아 거래 키와 함께 종결하며 생성은 다시 보내지 않는다.")
        @Test
        void recoversDroppedCreateResponseByOrderLookup() {
            Fixture fixture = fixture(1);
            TestPgControl.scenario(fixture.providerOrderId(), "{\"scenario\":\"DROP_RESPONSE\"}");
            PaymentCreateObservation created = paymentGateway.create(command(fixture, fixture.amount()));
            paymentResultService.applyCreateResponse(fixture.attemptId(), created);
            assertThat(attempt(fixture).getStatus()).isEqualTo(PaymentAttemptStatus.UNKNOWN);
            assertThat(stocks(fixture)).containsExactly(8L);
            TestPgControl.complete(fixture.providerOrderId());

            PaymentReflection reflection = paymentResultService.check(fixture.attemptId());

            assertThat(reflection.outcome()).isEqualTo(PaymentReflectionOutcome.APPLIED);
            assertThat(reflection.payment().attemptStatus()).isEqualTo(PaymentAttemptStatus.SUCCEEDED);
            assertThat(reflection.payment().transactionKey()).isNotBlank();
            TestPgControl.Stats stats = TestPgControl.stats(fixture.providerOrderId());
            assertThat(stats.createRequests()).isEqualTo(1);
            assertThat(stats.transactions()).isEqualTo(1);
        }

        @DisplayName("처리 중인 거래를 찾으면 거래 키와 PENDING을 기록하고, 같은 PENDING을 다시 확인하면 바꾸지 않는다.")
        @Test
        void recordsPendingTransactionOnce() {
            Fixture fixture = fixture(1);
            paymentGateway.create(command(fixture, fixture.amount()));
            paymentResultService.applyCreateResponse(fixture.attemptId(), new PaymentCreateObservation.Unknown("timeout"));

            PaymentReflection first = paymentResultService.check(fixture.attemptId());
            PaymentReflection second = paymentResultService.check(fixture.attemptId());

            assertThat(first.outcome()).isEqualTo(PaymentReflectionOutcome.APPLIED);
            assertThat(first.payment().attemptStatus()).isEqualTo(PaymentAttemptStatus.PENDING);
            assertThat(second.outcome()).isEqualTo(PaymentReflectionOutcome.UNCHANGED);
            assertThat(second.payment().transactionKey()).isEqualTo(first.payment().transactionKey());
            assertThat(order(fixture).getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        }

        @DisplayName("거래 0건·404·조회 불가는 상태를 바꾸지 않고, 조회 불가는 따로 알린다.")
        @Test
        void keepsUnconfirmedAttemptWithoutEvidence() {
            Fixture fixture = fixture(1);

            PaymentReflection empty = paymentResultService.check(fixture.attemptId());
            PaymentReflection notFound = paymentResultService.applyLookup(
                fixture.attemptId(), new PaymentLookupObservation.NotFound());
            PaymentReflection unavailable = paymentResultService.applyLookup(
                fixture.attemptId(), new PaymentLookupObservation.Unavailable("timeout"));

            assertThat(empty.outcome()).isEqualTo(PaymentReflectionOutcome.UNCHANGED);
            assertThat(notFound.outcome()).isEqualTo(PaymentReflectionOutcome.UNCHANGED);
            assertThat(unavailable.outcome()).isEqualTo(PaymentReflectionOutcome.LOOKUP_UNAVAILABLE);
            assertThat(unavailable.payment().attemptStatus()).isEqualTo(PaymentAttemptStatus.REQUESTED);
            assertThat(order(fixture).getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
            assertThat(stocks(fixture)).containsExactly(8L);
        }
    }

    @Nested
    class Duplicates {

        @DisplayName("같은 실패 관찰을 두 번 반영해도 종결과 재고 반환은 한 번이다.")
        @Test
        void returnsStockOnceForDuplicateFailure() {
            Fixture fixture = fixture(1);

            PaymentReflection first = paymentResultService.applyLookup(
                fixture.attemptId(), found(fixture, "TX-1", PgTransactionStatus.FAILED));
            PaymentReflection duplicate = paymentResultService.applyLookup(
                fixture.attemptId(), found(fixture, "TX-1", PgTransactionStatus.FAILED));

            assertThat(first.outcome()).isEqualTo(PaymentReflectionOutcome.APPLIED);
            assertThat(duplicate.outcome()).isEqualTo(PaymentReflectionOutcome.UNCHANGED);
            assertThat(stocks(fixture)).containsExactly(INITIAL_STOCK);
        }

        @DisplayName("같은 실패를 동시에 반영해도 한 요청만 종결하고 재고는 한 번만 반환한다.")
        @Test
        void returnsStockOnceForConcurrentFailure() throws Exception {
            Fixture fixture = fixture(1);
            PaymentLookupObservation failure = found(fixture, "TX-1", PgTransactionStatus.FAILED);
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                List<Future<PaymentReflectionOutcome>> futures = new ArrayList<>();
                for (int index = 0; index < 2; index++) {
                    futures.add(executor.submit(() -> {
                        start.await();
                        return paymentResultService.applyLookup(fixture.attemptId(), failure).outcome();
                    }));
                }
                start.countDown();

                List<PaymentReflectionOutcome> outcomes = new ArrayList<>();
                for (Future<PaymentReflectionOutcome> future : futures) {
                    outcomes.add(future.get(10, TimeUnit.SECONDS));
                }

                assertThat(outcomes).containsExactlyInAnyOrder(
                    PaymentReflectionOutcome.APPLIED, PaymentReflectionOutcome.UNCHANGED);
                assertThat(order(fixture).getStatus()).isEqualTo(OrderStatus.PAYMENT_FAILED);
                assertThat(stocks(fixture)).containsExactly(INITIAL_STOCK);
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Nested
    class Conflicts {

        @DisplayName("다른 주문의 성공 거래는 충돌로 기록하고 시도·주문·확보 재고를 유지한다.")
        @Test
        void recordsProviderOrderMismatchWithoutApplyingSuccess() {
            Fixture fixture = fixture(1);
            PaymentLookupObservation otherOrder = new PaymentLookupObservation.Found(List.of(
                new PgTransaction("TX-OTHER", "LP-ORD-9999999999", fixture.amount(), PgTransactionStatus.SUCCESS)));

            PaymentReflection reflection = paymentResultService.applyLookup(fixture.attemptId(), otherOrder);

            assertThat(reflection.outcome()).isEqualTo(PaymentReflectionOutcome.CONFLICT);
            assertThat(reflection.payment().conflictReason()).isEqualTo(PaymentConflictReason.TRANSACTION_MISMATCH);
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                entityManager.flush();
                entityManager.clear();
                assertThat(attempt(fixture).getStatus()).isEqualTo(PaymentAttemptStatus.REQUESTED);
                assertThat(attempt(fixture).getTransactionKey()).isNull();
                assertThat(attempt(fixture).getConflictReason()).isEqualTo(PaymentConflictReason.TRANSACTION_MISMATCH);
                assertThat(order(fixture).getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
                assertThat(stocks(fixture)).containsExactly(8L);
            });
        }

        @DisplayName("주문 조회에서 복수 거래를 찾으면 충돌을 보존하고 재발송·종결·재고 반환을 하지 않는다.")
        @ParameterizedTest(name = "거래 {0}건")
        @ValueSource(ints = {2, 3})
        void recordsMultipleTransactionsWithoutChoosingOne(int transactionCount) {
            Fixture fixture = fixture(1);
            Point point = Point.create(USER_ID);
            point.charge(100_000L);
            pointRepository.save(point);
            for (int index = 0; index < transactionCount; index++) {
                assertThat(paymentGateway.create(command(fixture, fixture.amount())))
                    .isInstanceOf(PaymentCreateObservation.Accepted.class);
            }
            paymentResultService.applyCreateResponse(fixture.attemptId(), new PaymentCreateObservation.Unknown("timeout"));
            TestPgControl.complete(fixture.providerOrderId());

            PaymentReflection first = paymentResultService.check(fixture.attemptId());
            ZonedDateTime firstDetectedAt = attempt(fixture).getConflictDetectedAt();
            PaymentReflection repeated = paymentResultService.check(fixture.attemptId());

            assertThat(first.outcome()).isEqualTo(PaymentReflectionOutcome.CONFLICT);
            assertThat(repeated.outcome()).isEqualTo(PaymentReflectionOutcome.CONFLICT);
            assertThat(first.payment().conflictReason()).isNotNull().hasToString("MULTIPLE_TRANSACTIONS");
            assertThat(repeated.payment().paymentProgress()).isEqualTo(PaymentProgress.UNCONFIRMED);
            assertThat(firstDetectedAt).isNotNull();
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                entityManager.flush();
                entityManager.clear();
                PaymentAttempt reloaded = attempt(fixture);
                assertThat(reloaded.getConflictReason()).isNotNull().hasToString("MULTIPLE_TRANSACTIONS");
                assertThat(reloaded.getConflictDetectedAt()).isEqualTo(firstDetectedAt);
                assertThat(reloaded.getStatus()).isEqualTo(PaymentAttemptStatus.UNKNOWN);
                assertThat(reloaded.getTransactionKey()).isNull();
                assertThat(order(fixture).getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
                assertThat(stocks(fixture)).containsExactly(8L);
                assertThat(pointRepository.findByUserId(USER_ID).orElseThrow().getBalance().amount()).isEqualTo(100_000L);
            });
            TestPgControl.Stats stats = TestPgControl.stats(fixture.providerOrderId());
            assertThat(stats.createRequests()).isEqualTo(transactionCount);
            assertThat(stats.transactions()).isEqualTo(transactionCount);
            assertThat(stats.lookupRequests()).isEqualTo(2);
        }

        @DisplayName("거래 키를 이미 확인했어도 복수 거래의 첫 실패를 적용하거나 예약을 반환하지 않는다.")
        @Test
        void preservesPendingAttemptAndKnownKeyForMultipleTransactions() {
            Fixture fixture = fixture(1);
            paymentResultService.applyCreateResponse(fixture.attemptId(), new PaymentCreateObservation.Accepted("TX-1"));
            PaymentLookupObservation multiple = new PaymentLookupObservation.Found(List.of(
                new PgTransaction("TX-1", fixture.providerOrderId(), fixture.amount(), PgTransactionStatus.FAILED),
                new PgTransaction("TX-2", fixture.providerOrderId(), fixture.amount(), PgTransactionStatus.SUCCESS)));

            PaymentReflection reflection = paymentResultService.applyLookup(fixture.attemptId(), multiple);

            assertThat(reflection.outcome()).isEqualTo(PaymentReflectionOutcome.CONFLICT);
            assertThat(reflection.payment().conflictReason()).isNotNull().hasToString("MULTIPLE_TRANSACTIONS");
            assertThat(reflection.payment().transactionKey()).isEqualTo("TX-1");
            assertThat(reflection.payment().attemptStatus()).isEqualTo(PaymentAttemptStatus.PENDING);
            assertThat(reflection.payment().orderStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
            assertThat(reflection.payment().paymentProgress()).isEqualTo(PaymentProgress.UNCONFIRMED);
            assertThat(stocks(fixture)).containsExactly(8L);
        }

        @DisplayName("종결 뒤 복수 거래를 관찰해도 기존 성공과 거래 키·재고를 유지하고 충돌만 기록한다.")
        @Test
        void preservesTerminalResultForMultipleTransactions() {
            Fixture fixture = fixture(1);
            paymentResultService.applyLookup(fixture.attemptId(), found(fixture, "TX-1", PgTransactionStatus.SUCCESS));
            PaymentLookupObservation multiple = new PaymentLookupObservation.Found(List.of(
                new PgTransaction("TX-2", fixture.providerOrderId(), fixture.amount(), PgTransactionStatus.FAILED),
                new PgTransaction("TX-1", fixture.providerOrderId(), fixture.amount(), PgTransactionStatus.SUCCESS)));

            PaymentReflection reflection = paymentResultService.applyLookup(fixture.attemptId(), multiple);

            assertThat(reflection.outcome()).isEqualTo(PaymentReflectionOutcome.CONFLICT);
            assertThat(reflection.payment().conflictReason()).isNotNull().hasToString("MULTIPLE_TRANSACTIONS");
            assertThat(reflection.payment().transactionKey()).isEqualTo("TX-1");
            assertThat(reflection.payment().attemptStatus()).isEqualTo(PaymentAttemptStatus.SUCCEEDED);
            assertThat(reflection.payment().orderStatus()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(stocks(fixture)).containsExactly(8L);
        }

        @DisplayName("거래 금액이 시도와 다르면 TRANSACTION_MISMATCH를 기록하고 거래 키를 저장하지 않는다.")
        @Test
        void recordsAmountMismatch() {
            Fixture fixture = fixture(1);
            paymentGateway.create(command(fixture, fixture.amount() + 1));

            PaymentReflection reflection = paymentResultService.check(fixture.attemptId());

            assertThat(reflection.outcome()).isEqualTo(PaymentReflectionOutcome.CONFLICT);
            assertThat(reflection.payment().conflictReason()).isEqualTo(PaymentConflictReason.TRANSACTION_MISMATCH);
            assertThat(reflection.payment().transactionKey()).isNull();
            assertThat(stocks(fixture)).containsExactly(8L);
        }

        @DisplayName("기록한 거래 키와 다른 접수 응답은 TRANSACTION_MISMATCH이고 기록한 키를 유지한다.")
        @Test
        void recordsDifferentAcceptedKey() {
            Fixture fixture = fixture(1);
            paymentResultService.applyCreateResponse(fixture.attemptId(), new PaymentCreateObservation.Accepted("TX-1"));

            PaymentReflection reflection = paymentResultService.applyCreateResponse(
                fixture.attemptId(), new PaymentCreateObservation.Accepted("TX-2"));

            assertThat(reflection.outcome()).isEqualTo(PaymentReflectionOutcome.CONFLICT);
            assertThat(reflection.payment().conflictReason()).isEqualTo(PaymentConflictReason.TRANSACTION_MISMATCH);
            assertThat(reflection.payment().transactionKey()).isEqualTo("TX-1");
            assertThat(reflection.payment().attemptStatus()).isEqualTo(PaymentAttemptStatus.PENDING);
        }

        @DisplayName("조회와 갱신 사이에 성공으로 종결된 시도에 실패가 관찰되면 RESULT_CONTRADICTION을 기록하고 성공을 유지한다.")
        @Test
        void recordsContradictionAfterTermination() {
            Fixture fixture = fixture(1);
            paymentResultService.applyLookup(fixture.attemptId(), found(fixture, "TX-1", PgTransactionStatus.SUCCESS));

            PaymentReflection reflection = paymentResultService.applyLookup(
                fixture.attemptId(), found(fixture, "TX-1", PgTransactionStatus.FAILED));

            assertThat(reflection.outcome()).isEqualTo(PaymentReflectionOutcome.CONFLICT);
            assertThat(reflection.payment().conflictReason()).isEqualTo(PaymentConflictReason.RESULT_CONTRADICTION);
            assertThat(reflection.payment().attemptStatus()).isEqualTo(PaymentAttemptStatus.SUCCEEDED);
            assertThat(reflection.payment().orderStatus()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(stocks(fixture)).containsExactly(8L);
        }

        @DisplayName("충돌이 반복돼도 처음 기록한 사유와 시각을 유지한다.")
        @Test
        void keepsFirstConflict() {
            Fixture fixture = fixture(1);
            paymentResultService.applyCreateResponse(fixture.attemptId(), new PaymentCreateObservation.Accepted("TX-1"));
            PaymentLookupObservation mismatchedAmount = new PaymentLookupObservation.Found(
                List.of(new PgTransaction("TX-1", fixture.providerOrderId(), fixture.amount() + 1, PgTransactionStatus.PENDING))
            );
            paymentResultService.applyLookup(fixture.attemptId(), mismatchedAmount);
            ZonedDateTime firstDetectedAt = attempt(fixture).getConflictDetectedAt();

            PaymentReflection repeated = paymentResultService.applyLookup(fixture.attemptId(), mismatchedAmount);
            PaymentReflection contradiction = paymentResultService.applyCreateResponse(
                fixture.attemptId(), new PaymentCreateObservation.RejectedNoEffect());

            assertThat(repeated.outcome()).isEqualTo(PaymentReflectionOutcome.CONFLICT);
            assertThat(contradiction.outcome()).isEqualTo(PaymentReflectionOutcome.CONFLICT);
            assertThat(firstDetectedAt).isNotNull();
            assertThat(attempt(fixture).getConflictReason()).isEqualTo(PaymentConflictReason.TRANSACTION_MISMATCH);
            assertThat(attempt(fixture).getConflictDetectedAt()).isEqualTo(firstDetectedAt);
            assertThat(attempt(fixture).getStatus()).isEqualTo(PaymentAttemptStatus.PENDING);
        }
    }

    @Nested
    class Failures {

        @DisplayName("실제 PG 성공 뒤 주문 저장이 실패해도 같은 거래를 재조회해 한 번 확정하고 create를 추가하지 않는다.")
        @Test
        void recoversPgSuccessAfterRollbackWithoutCreatingAgain() {
            Fixture fixture = fixture(1);
            Point point = Point.create(USER_ID);
            point.charge(100_000L);
            pointRepository.save(point);
            PaymentCreateObservation created = paymentGateway.create(command(fixture, fixture.amount()));
            assertThat(created).isInstanceOf(PaymentCreateObservation.Accepted.class);
            String transactionKey = ((PaymentCreateObservation.Accepted) created).transactionKey();
            assertThat(TestPgControl.complete(fixture.providerOrderId())).isEqualTo(1);
            AtomicInteger completions = new AtomicInteger();
            doAnswer(invocation -> {
                if (completions.incrementAndGet() == 1) {
                    throw new DataAccessResourceFailureException("PG 성공 뒤 주문 저장 실패 주입");
                }
                return invocation.callRealMethod();
            }).when(orderRepository).completePgPaymentIfPending(anyLong(), anyLong(), any(ZonedDateTime.class));

            assertThatThrownBy(() -> paymentResultService.check(fixture.attemptId()))
                .isInstanceOf(PaymentReflectionRolledBackException.class)
                .hasCauseInstanceOf(DataAccessResourceFailureException.class);

            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                entityManager.flush();
                entityManager.clear();
                assertThat(attempt(fixture).getStatus()).isEqualTo(PaymentAttemptStatus.REQUESTED);
                assertThat(attempt(fixture).getTransactionKey()).isNull();
                assertThat(order(fixture).getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
                assertThat(stocks(fixture)).containsExactly(8L);
                assertThat(pointRepository.findByUserId(USER_ID).orElseThrow().getBalance().amount()).isEqualTo(100_000L);
            });
            assertThat(TestPgControl.stats(fixture.providerOrderId()).lookupRequests()).isEqualTo(1);

            PaymentReflection retried = paymentResultService.check(fixture.attemptId());
            PaymentReflection duplicate = paymentResultService.check(fixture.attemptId());

            assertThat(retried.outcome()).isEqualTo(PaymentReflectionOutcome.APPLIED);
            assertThat(retried.payment().orderStatus()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(retried.payment().transactionKey()).isEqualTo(transactionKey);
            assertThat(duplicate.outcome()).isEqualTo(PaymentReflectionOutcome.UNCHANGED);
            assertThat(completions).hasValue(2);
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                entityManager.flush();
                entityManager.clear();
                assertThat(attempt(fixture).getStatus()).isEqualTo(PaymentAttemptStatus.SUCCEEDED);
                assertThat(attempt(fixture).getTransactionKey()).isEqualTo(transactionKey);
                assertThat(order(fixture).getStatus()).isEqualTo(OrderStatus.CONFIRMED);
                assertThat(stocks(fixture)).containsExactly(8L);
                assertThat(pointRepository.findByUserId(USER_ID).orElseThrow().getBalance().amount()).isEqualTo(100_000L);
            });
            TestPgControl.Stats stats = TestPgControl.stats(fixture.providerOrderId());
            assertThat(stats.createRequests()).isEqualTo(1);
            assertThat(stats.transactions()).isEqualTo(1);
            assertThat(stats.lookupRequests()).isEqualTo(2);
        }

        @DisplayName("여러 상품 중 일부를 반환한 뒤 실패하면 TX B 전체를 되돌리고, 다음 확인에서 한 번 반영한다.")
        @Test
        void rollsBackPartialStockReturn() {
            Fixture fixture = fixture(2);
            AtomicInteger increases = new AtomicInteger();
            doAnswer(invocation -> {
                if (increases.incrementAndGet() == 2) {
                    throw new DataAccessResourceFailureException("두 번째 재고 반환 실패 주입");
                }
                return invocation.callRealMethod();
            }).when(productRepository).increaseStock(anyLong(), anyInt(), any(ZonedDateTime.class));
            PaymentLookupObservation failure = found(fixture, "TX-1", PgTransactionStatus.FAILED);

            assertThatThrownBy(() -> paymentResultService.applyLookup(fixture.attemptId(), failure))
                .isInstanceOf(PaymentReflectionRolledBackException.class)
                .hasCauseInstanceOf(DataAccessResourceFailureException.class);

            assertThat(attempt(fixture).getStatus()).isEqualTo(PaymentAttemptStatus.REQUESTED);
            assertThat(attempt(fixture).getTransactionKey()).isNull();
            assertThat(order(fixture).getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
            assertThat(stocks(fixture)).containsExactly(8L, 8L);

            PaymentReflection retried = paymentResultService.applyLookup(fixture.attemptId(), failure);

            assertThat(retried.outcome()).isEqualTo(PaymentReflectionOutcome.APPLIED);
            assertThat(stocks(fixture)).containsExactly(INITIAL_STOCK, INITIAL_STOCK);
        }

        @DisplayName("시도 종결 뒤 주문 갱신에서 실패하면 시도 종결도 되돌리고, 다음 확인에서 한 번 확정한다.")
        @Test
        void rollsBackAttemptWhenOrderUpdateFails() {
            Fixture fixture = fixture(1);
            AtomicInteger completions = new AtomicInteger();
            doAnswer(invocation -> {
                if (completions.incrementAndGet() == 1) {
                    throw new DataAccessResourceFailureException("주문 확정 실패 주입");
                }
                return invocation.callRealMethod();
            }).when(orderRepository).completePgPaymentIfPending(anyLong(), anyLong(), any(ZonedDateTime.class));
            PaymentLookupObservation success = found(fixture, "TX-1", PgTransactionStatus.SUCCESS);

            assertThatThrownBy(() -> paymentResultService.applyLookup(fixture.attemptId(), success))
                .isInstanceOf(PaymentReflectionRolledBackException.class)
                .hasCauseInstanceOf(DataAccessResourceFailureException.class);

            assertThat(attempt(fixture).getStatus()).isEqualTo(PaymentAttemptStatus.REQUESTED);
            assertThat(order(fixture).getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);

            PaymentReflection retried = paymentResultService.applyLookup(fixture.attemptId(), success);

            assertThat(retried.outcome()).isEqualTo(PaymentReflectionOutcome.APPLIED);
            assertThat(retried.payment().orderStatus()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(stocks(fixture)).containsExactly(8L);
        }

        @DisplayName("TX B commit 뒤 응답용 재조회 실패는 rollback과 구분해 그대로 알리고, 종결과 재고 반환은 유지되며 다시 반영해도 추가 반환이 없다.")
        @Test
        void keepsCommittedResultWhenResponseReadFails() {
            Fixture fixture = fixture(1);
            AtomicInteger orderReads = new AtomicInteger();
            doAnswer(invocation -> {
                if (orderReads.incrementAndGet() == 2) {
                    throw new DataAccessResourceFailureException("응답용 재조회 실패 주입");
                }
                return invocation.callRealMethod();
            }).when(orderRepository).findById(fixture.orderId());
            PaymentLookupObservation failure = found(fixture, "TX-1", PgTransactionStatus.FAILED);

            assertThatThrownBy(() -> paymentResultService.applyLookup(fixture.attemptId(), failure))
                .isInstanceOf(DataAccessResourceFailureException.class)
                .isNotInstanceOf(PaymentReflectionRolledBackException.class);

            assertThat(attempt(fixture).getStatus()).isEqualTo(PaymentAttemptStatus.FAILED);
            assertThat(order(fixture).getStatus()).isEqualTo(OrderStatus.PAYMENT_FAILED);
            assertThat(stocks(fixture)).containsExactly(INITIAL_STOCK);

            PaymentReflection again = paymentResultService.applyLookup(fixture.attemptId(), failure);

            assertThat(again.outcome()).isEqualTo(PaymentReflectionOutcome.UNCHANGED);
            assertThat(stocks(fixture)).containsExactly(INITIAL_STOCK);
        }

        @DisplayName("미종결 시도의 주문이 PAYMENT_PENDING이 아니면 정합성 오류로 되돌리고 시도·주문·재고를 바꾸지 않는다.")
        @Test
        void rejectsResultForNonPendingOrderAsIntegrityError() {
            Fixture fixture = fixture(1);
            jdbcTemplate.update("UPDATE orders SET status = 'CONFIRMED' WHERE id = ?", fixture.orderId());

            assertInternalError(() -> paymentResultService.applyLookup(
                fixture.attemptId(), found(fixture, "TX-1", PgTransactionStatus.FAILED)));
            // 정합성 오류는 재전송으로 풀리지 않으므로 일시 장애(rollback 알림)로 감싸지 않는다.
            assertInternalError(() -> paymentResultService.applyLookup(
                fixture.attemptId(), found(fixture, "TX-1", PgTransactionStatus.SUCCESS)));

            assertThat(attempt(fixture).getStatus()).isEqualTo(PaymentAttemptStatus.REQUESTED);
            assertThat(attempt(fixture).getTransactionKey()).isNull();
            assertThat(order(fixture).getStatus()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(order(fixture).getPaymentAmount()).isNull();
            assertThat(stocks(fixture)).containsExactly(8L);
        }
    }

    @Nested
    class DeletedProducts {

        @DisplayName("재고 차감 뒤 상품이 삭제돼도 실패는 재고를 반환하고 삭제 상태를 유지한다.")
        @Test
        void returnsStockOfDeletedProduct() {
            Fixture fixture = fixture(1);
            deleteProduct(fixture.productIds().getFirst());

            paymentResultService.applyLookup(fixture.attemptId(), found(fixture, "TX-1", PgTransactionStatus.FAILED));

            Product product = productRepository.findById(fixture.productIds().getFirst()).orElseThrow();
            assertThat(product.getStock().amount()).isEqualTo(INITIAL_STOCK);
            assertThat(product.getDeletedAt()).isNotNull();
        }

        @DisplayName("재고 차감 뒤 상품이 삭제돼도 성공은 저장된 품목·금액으로 확정한다.")
        @Test
        void confirmsOrderOfDeletedProduct() {
            Fixture fixture = fixture(1);
            deleteProduct(fixture.productIds().getFirst());

            PaymentReflection reflection = paymentResultService.applyLookup(
                fixture.attemptId(), found(fixture, "TX-1", PgTransactionStatus.SUCCESS));

            assertThat(reflection.payment().orderStatus()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(order(fixture).getPaymentAmount()).isEqualTo(fixture.amount());
            assertThat(stocks(fixture)).containsExactly(8L);
        }
    }

    @DisplayName("바깥 트랜잭션 안에서 호출하면 TX B 경계가 합쳐지지 않도록 거절한다.")
    @Test
    void rejectsCallInsideOuterTransaction() {
        Fixture fixture = fixture(1);

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager)
            .executeWithoutResult(status -> paymentResultService.check(fixture.attemptId())))
            .isInstanceOf(IllegalTransactionStateException.class);
    }

    private Fixture fixture(int productCount) {
        Brand brand = brandRepository.save(Brand.create("brand-" + UUID.randomUUID()));
        List<Long> productIds = new ArrayList<>();
        List<OrderItem> items = new ArrayList<>();
        for (int index = 0; index < productCount; index++) {
            Product product = Product.create(brand.getId(), "상품-" + index, PRICE);
            product.changeStockTo(INITIAL_STOCK - QUANTITY);
            Product saved = productRepository.save(product);
            productIds.add(saved.getId());
            items.add(OrderItem.create(saved.getId(), saved.getName(), PRICE, QUANTITY));
        }
        Order order = orderRepository.save(Order.create(USER_ID, items));
        order.requestPgPayment();
        orderRepository.save(order);
        PaymentAttempt attempt = paymentAttemptRepository.save(PaymentAttempt.create(
            UUID.randomUUID().toString(), order.getId(), order.getTotalAmount(), UUID.randomUUID().toString().substring(0, 8)
        ));
        return new Fixture(attempt.getId(), order.getId(), attempt.getProviderOrderId(), order.getTotalAmount(), productIds);
    }

    private void deleteProduct(Long productId) {
        new TransactionTemplate(transactionManager).executeWithoutResult(
            status -> productRepository.softDeleteActiveById(productId, ZonedDateTime.now()));
    }

    private String acceptInPg(Fixture fixture) {
        PaymentCreateObservation created = paymentGateway.create(command(fixture, fixture.amount()));
        assertThat(created).isInstanceOf(PaymentCreateObservation.Accepted.class);
        paymentResultService.applyCreateResponse(fixture.attemptId(), created);
        return ((PaymentCreateObservation.Accepted) created).transactionKey();
    }

    private PaymentCreateCommand command(Fixture fixture, long amount) {
        return new PaymentCreateCommand(fixture.providerOrderId(), amount, "TEST", "0000-0000-0000-0000");
    }

    private PaymentLookupObservation found(Fixture fixture, String transactionKey, PgTransactionStatus status) {
        return new PaymentLookupObservation.Found(List.of(
            new PgTransaction(transactionKey, fixture.providerOrderId(), fixture.amount(), status)
        ));
    }

    private PaymentAttempt attempt(Fixture fixture) {
        return paymentAttemptRepository.findById(fixture.attemptId()).orElseThrow();
    }

    private Order order(Fixture fixture) {
        return orderRepository.findById(fixture.orderId()).orElseThrow();
    }

    private List<Long> stocks(Fixture fixture) {
        return fixture.productIds().stream()
            .map(productId -> productRepository.findById(productId).orElseThrow().getStock().amount())
            .toList();
    }

    private void assertInternalError(Runnable reflection) {
        assertThatThrownBy(reflection::run)
            .isInstanceOfSatisfying(CoreException.class, exception ->
                assertThat(exception.getErrorType()).isEqualTo(ErrorType.INTERNAL_ERROR));
    }

    private record Fixture(Long attemptId, Long orderId, String providerOrderId, long amount, List<Long> productIds) {}
}
