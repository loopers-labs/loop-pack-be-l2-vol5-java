package com.loopers.application.order.facade;

import com.loopers.application.order.facade.OrderPaymentFacade.ConfirmCommand;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderErrorCode;
import com.loopers.domain.order.OrderLine;
import com.loopers.domain.payment.entity.Payment;
import com.loopers.domain.payment.enums.PaymentErrorCode;
import com.loopers.domain.payment.vo.PaymentLookupResult;
import com.loopers.domain.payment.vo.ProviderTransaction;
import com.loopers.domain.payment.enums.ProviderTransactionStatus;
import com.loopers.domain.point.Point;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductDetailCache;
import com.loopers.domain.product.ProductListCache;
import com.loopers.support.error.CoreException;
import com.loopers.infrastructure.payment.client.TestPgLookupPolicyProperties;
import com.loopers.support.pg.TestPgGateways;
import com.loopers.support.pg.TestPgServer;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;
import com.loopers.application.order.OrderInfo;

/** 5주차 설계 10 의 장면. PG 응답 방식을 고정하고 외부 거래 수 · create 횟수와 DB 상태를 함께 비교함 */
@SpringBootTest
class PaymentIntegrationTest {

    private static final Long USER_ID = 1L;
    private static final long INITIAL_BALANCE = 10_000L;

    @Autowired
    private OrderPaymentFacade orderPaymentFacade;

    @Autowired
    private OrderPaymentSteps orderPaymentSteps;

    @Autowired
    private ProductListCache productListCache;

    @Autowired
    private ProductDetailCache productDetailCache;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private final TestPgServer pg = TestPgServer.shared();
    private Product product;

    @BeforeEach
    void setUp() {
        pg.reset();
        transactionTemplate.executeWithoutResult(status -> {
            Brand brand = new Brand("브랜드", null);
            entityManager.persist(brand);
            product = new Product(brand, "상품", 1_000L);
            product.changeStock(5);
            entityManager.persist(product);
            Point point = new Point(USER_ID);
            point.charge(INITIAL_BALANCE);
            entityManager.persist(point);
        });
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private Order persistDraft(int quantity) {
        return transactionTemplate.execute(status -> {
            Order order = Order.draft(USER_ID, List.of(new OrderLine(product.getId(), product.getName(), product.getPrice(), quantity)),
                ZonedDateTime.now());
            entityManager.persist(order);
            return order;
        });
    }

    private OrderInfo confirm(Order order) {
        return orderPaymentFacade.confirm(USER_ID, order.getId(), new ConfirmCommand("attempt-" + order.getId(), "TEST", "0000-0000-0000-0000"));
    }

    private OrderInfo reconcile(Order order) {
        return orderPaymentFacade.reconcile(USER_ID, order.getId());
    }

    private static String providerOrderIdOf(Order order) {
        return Payment.providerOrderIdOf(order.getId());
    }

    private String onlyTransactionKeyOf(Order order) {
        List<TestPgServer.Transaction> transactions = pg.transactionsOf(providerOrderIdOf(order));
        assertThat(transactions).hasSize(1);
        return transactions.get(0).transactionKey();
    }


    private int stock() {
        return ((Number) query("select stock from product where id = " + product.getId())).intValue();
    }

    private long balance() {
        return ((Number) query("select balance from points where user_id = " + USER_ID)).longValue();
    }

    private String orderStatusOf(Order order) {
        return (String) query("select status from orders where id = " + order.getId());
    }

    /** 결제 상태, 예약 상태, 충돌 사유(없으면 "-") */
    private List<String> paymentStateOf(Order order) {
        Object[] row = (Object[]) transactionTemplate.execute(status -> entityManager.createNativeQuery(
            "select status, reservation_status, conflict_reason from payment where order_id = " + order.getId()).getSingleResult());
        return List.of((String) row[0], (String) row[1], row[2] == null ? "-" : (String) row[2]);
    }

    private Object query(String sql) {
        return transactionTemplate.execute(status -> entityManager.createNativeQuery(sql).getSingleResult());
    }

    @DisplayName("정상 PG 주문은, ")
    @Nested
    class Normal {
        @DisplayName("결제 시작 뒤 거래 SUCCESS 를 확인하면, 외부 거래 1 · CONFIRMED · 예약 소비이고 가용 재고는 더 줄지 않으며 포인트 변화는 0 이다.")
        @Test
        void confirmsWithOneTransaction() {
            // arrange
            Order order = persistDraft(2);
            confirm(order);
            pg.complete(onlyTransactionKeyOf(order), "SUCCESS");

            // act
            OrderInfo result = reconcile(order);

            // assert
            assertAll(
                () -> assertThat(result.status()).isEqualTo("CONFIRMED"),
                () -> assertThat(result.paymentAmount()).isEqualTo(2_000L),
                () -> assertThat(pg.transactions()).hasSize(1),
                () -> assertThat(pg.createCount()).isEqualTo(1),
                () -> assertThat(orderStatusOf(order)).isEqualTo("CONFIRMED"),
                () -> assertThat(paymentStateOf(order)).containsExactly("SUCCESS", "CONSUMED", "-"),
                () -> assertThat(stock()).isEqualTo(3),
                () -> assertThat(balance()).isEqualTo(INITIAL_BALANCE)
            );
        }

        @DisplayName("생성 수락(PENDING) 뒤 거래가 아직 처리 중이면 확정하지 않고 예약을 유지하며, SUCCESS 를 확인한 뒤에만 확정한다.")
        @Test
        void confirmsOnlyAfterSuccessIsObserved() {
            // arrange
            Order order = persistDraft(2);
            OrderInfo started = confirm(order);

            // act
            OrderInfo stillPending = reconcile(order);
            pg.complete(onlyTransactionKeyOf(order), "SUCCESS");
            OrderInfo confirmed = reconcile(order);

            // assert
            assertAll(
                () -> assertThat(started.status()).isEqualTo("PAYMENT_PENDING"),
                () -> assertThat(stillPending.status()).isEqualTo("PAYMENT_PENDING"),
                () -> assertThat(stillPending.paymentStatus()).isEqualTo("PENDING"),
                () -> assertThat(confirmed.status()).isEqualTo("CONFIRMED"),
                () -> assertThat(stock()).isEqualTo(3)
            );
        }
    }

    @DisplayName("확정 실패는, ")
    @Nested
    class Failure {
        @DisplayName("효과 0건 확정 거절이면, 외부 거래 0 · 주문 PAYMENT_FAILED 이고 재고 5 에서 2 를 확보했다가 5 로 한 번 돌아온다.")
        @Test
        void releasesOnce_whenRejectedWithoutEffect() {
            // arrange
            pg.willRejectBeforeCreate();
            Order order = persistDraft(2);

            // act
            OrderInfo result = confirm(order);

            // assert
            assertAll(
                () -> assertThat(result.status()).isEqualTo("PAYMENT_FAILED"),
                () -> assertThat(pg.transactions()).isEmpty(),
                () -> assertThat(paymentStateOf(order)).containsExactly("REJECTED", "RELEASED", "-"),
                () -> assertThat(stock()).isEqualTo(5),
                () -> assertThat(balance()).isEqualTo(INITIAL_BALANCE)
            );
        }

        @DisplayName("거래 FAILED 를 확인하면 한 번 반환하고, 같은 결과를 여러 번 확인해도 가용 재고는 5 이며 7 이 되지 않는다.")
        @Test
        void releasesOnlyOnce_whenFailureIsObservedRepeatedly() {
            // arrange
            Order order = persistDraft(2);
            confirm(order);
            pg.complete(onlyTransactionKeyOf(order), "FAILED");

            // act: 종결 뒤 reconcile 은 조회하지 않으므로, 늦게 도착한 같은 조회 결과는 결과 적용 경계에 직접 넣음
            reconcile(order);
            reconcile(order);
            orderPaymentSteps.applyLookupResult(order.getId(), new PaymentLookupResult.Found(List.of(
                new ProviderTransaction(onlyTransactionKeyOf(order), providerOrderIdOf(order), 2_000L, ProviderTransactionStatus.FAILED))));

            // assert
            assertAll(
                () -> assertThat(orderStatusOf(order)).isEqualTo("PAYMENT_FAILED"),
                () -> assertThat(paymentStateOf(order)).containsExactly("FAILED", "RELEASED", "-"),
                () -> assertThat(stock()).isEqualTo(5)
            );
        }

        @DisplayName("결과 확인 전 상품이 삭제돼도, 확정 실패면 예약을 반환하고 상품은 삭제된 채로 남는다.")
        @Test
        void releasesToDeletedProduct_keepingItDeleted() {
            // arrange
            Order order = persistDraft(2);
            confirm(order);
            transactionTemplate.executeWithoutResult(status -> entityManager.find(Product.class, product.getId()).delete());
            pg.complete(onlyTransactionKeyOf(order), "FAILED");

            // act
            reconcile(order);

            // assert
            assertAll(
                () -> assertThat(orderStatusOf(order)).isEqualTo("PAYMENT_FAILED"),
                () -> assertThat(stock()).isEqualTo(5),
                () -> assertThat(query("select deleted_at is not null from product where id = " + product.getId())).isIn(1L, 1, true)
            );
        }
    }

    @DisplayName("결과를 확인하지 못한 결제는, ")
    @Nested
    class Unknown {
        @DisplayName("거래를 만든 뒤 응답만 유실되면, 외부 거래 1 · 결제 UNKNOWN · 예약 유지이고 create 를 다시 보내지 않으며 같은 거래 조회로 종결한다.")
        @Test
        void settlesBySameTransaction_afterResponseIsLost() {
            // arrange
            pg.willDropResponseAfterCreate();
            Order order = persistDraft(2);

            // act
            OrderInfo started = confirm(order);
            List<String> afterLoss = paymentStateOf(order);
            int stockAfterLoss = stock();
            pg.complete(onlyTransactionKeyOf(order), "SUCCESS");
            OrderInfo settled = reconcile(order);

            // assert
            assertAll(
                () -> assertThat(started.status()).isEqualTo("PAYMENT_PENDING"),
                () -> assertThat(started.paymentStatus()).isEqualTo("UNKNOWN"),
                () -> assertThat(afterLoss).containsExactly("UNKNOWN", "RESERVED", "-"),
                () -> assertThat(stockAfterLoss).isEqualTo(3),
                () -> assertThat(settled.status()).isEqualTo("CONFIRMED"),
                () -> assertThat(pg.transactions()).hasSize(1),
                () -> assertThat(pg.createCount()).isEqualTo(1),
                () -> assertThat(stock()).isEqualTo(3)
            );
        }

        @DisplayName("생성 응답이 응답 대기 한도를 넘기면, 결제 UNKNOWN · 예약 유지이고 실패로 처리하거나 재고를 반환하지 않는다.")
        @Test
        void keepsReservation_whenCreateTimesOut() {
            // arrange
            pg.willDelayResponseAfterCreate(Duration.ofSeconds(3));
            Order order = persistDraft(2);

            // act
            OrderInfo result = confirm(order);

            // assert
            assertAll(
                () -> assertThat(result.paymentStatus()).isEqualTo("UNKNOWN"),
                () -> assertThat(orderStatusOf(order)).isEqualTo("PAYMENT_PENDING"),
                () -> assertThat(stock()).isEqualTo(3),
                () -> assertThat(pg.createCount()).isEqualTo(1)
            );
        }

        @DisplayName("조회 결과가 0 건이면, 결제 미확인 · 예약을 유지하고 새 결제를 만들지 않는다.")
        @Test
        void keepsUnknown_whenNoTransactionIsFound() {
            // arrange
            pg.willRespondToCreate(500, "{}");
            Order order = persistDraft(2);
            confirm(order);

            // act
            OrderInfo result = reconcile(order);

            // assert
            assertAll(
                () -> assertThat(result.paymentStatus()).isEqualTo("UNKNOWN"),
                () -> assertThat(paymentStateOf(order)).containsExactly("UNKNOWN", "RESERVED", "-"),
                () -> assertThat(stock()).isEqualTo(3),
                () -> assertThat(pg.createCount()).isEqualTo(1)
            );
        }

        @DisplayName("같은 주문의 거래가 2 건이면, 한 건이 SUCCESS 여도 확정하지 않고 충돌을 기록하며 예약을 유지한다.")
        @Test
        void recordsConflict_whenMultipleTransactionsExist() {
            // arrange
            Order order = persistDraft(2);
            confirm(order);
            pg.complete(onlyTransactionKeyOf(order), "SUCCESS");
            pg.addTransaction(providerOrderIdOf(order), 2_000L, "SUCCESS");

            // act
            OrderInfo first = reconcile(order);
            OrderInfo second = reconcile(order);

            // assert
            assertAll(
                () -> assertThat(first.status()).isEqualTo("PAYMENT_PENDING"),
                () -> assertThat(second.status()).isEqualTo("PAYMENT_PENDING"),
                () -> assertThat(paymentStateOf(order)).containsExactly("PENDING", "RESERVED", "MULTIPLE_TRANSACTIONS"),
                () -> assertThat(stock()).isEqualTo(3),
                () -> assertThat(pg.createCount()).isEqualTo(1)
            );
        }

        @DisplayName("거래 금액이 저장된 금액과 다르면, 성공으로 반영하지 않고 충돌을 기록한다.")
        @Test
        void recordsConflict_whenAmountMismatches() {
            // arrange
            pg.willRespondToCreate(500, "{}");
            Order order = persistDraft(2);
            confirm(order);
            pg.addTransaction(providerOrderIdOf(order), 1_999L, "SUCCESS");

            // act
            OrderInfo result = reconcile(order);

            // assert
            assertAll(
                () -> assertThat(result.status()).isEqualTo("PAYMENT_PENDING"),
                () -> assertThat(paymentStateOf(order)).containsExactly("UNKNOWN", "RESERVED", "MISMATCHED_TRANSACTION"),
                () -> assertThat(stock()).isEqualTo(3)
            );
        }
    }

    @DisplayName("콜백과 reconcile 은, ")
    @Nested
    class CallbackAndReconcile {
        private OrderInfo callback(Order order) {
            return orderPaymentFacade.handleCallback(providerOrderIdOf(order));
        }

        @DisplayName("콜백이 먼저 와도, 같은 거래 키 · CONFIRMED 이고 뒤의 reconcile 은 조회 없이 저장 상태를 돌려준다.")
        @Test
        void settlesOnce_whenCallbackComesFirst() {
            // arrange
            Order order = persistDraft(2);
            confirm(order);
            String transactionKey = onlyTransactionKeyOf(order);
            pg.complete(transactionKey, "SUCCESS");

            // act
            OrderInfo byCallback = callback(order);
            int lookupsAfterCallback = pg.lookupCount();
            OrderInfo byReconcile = reconcile(order);

            // assert
            assertAll(
                () -> assertThat(byCallback.status()).isEqualTo("CONFIRMED"),
                () -> assertThat(byReconcile.status()).isEqualTo("CONFIRMED"),
                () -> assertThat(pg.lookupCount()).isEqualTo(lookupsAfterCallback),
                () -> assertThat(query("select transaction_key from payment where order_id = " + order.getId())).isEqualTo(transactionKey),
                () -> assertThat(paymentStateOf(order)).containsExactly("SUCCESS", "CONSUMED", "-"),
                () -> assertThat(stock()).isEqualTo(3),
                () -> assertThat(pg.createCount()).isEqualTo(1)
            );
        }

        @DisplayName("reconcile 이 먼저 종결하면, 뒤의 콜백은 조회 없이 끝나고 반환이 늘지 않는다.")
        @Test
        void addsNothing_whenCallbackComesAfterReconcile() {
            // arrange
            Order order = persistDraft(2);
            confirm(order);
            pg.complete(onlyTransactionKeyOf(order), "FAILED");
            reconcile(order);
            int lookupsAfterReconcile = pg.lookupCount();

            // act
            OrderInfo result = callback(order);

            // assert
            assertAll(
                () -> assertThat(result.status()).isEqualTo("PAYMENT_FAILED"),
                () -> assertThat(pg.lookupCount()).isEqualTo(lookupsAfterReconcile),
                () -> assertThat(paymentStateOf(order)).containsExactly("FAILED", "RELEASED", "-"),
                () -> assertThat(stock()).isEqualTo(5)
            );
        }

        @DisplayName("같은 콜백이 두 번 와도, 재고는 한 번만 반환된다.")
        @Test
        void releasesOnce_whenCallbackIsDuplicated() {
            // arrange
            Order order = persistDraft(2);
            confirm(order);
            pg.complete(onlyTransactionKeyOf(order), "FAILED");

            // act
            callback(order);
            OrderInfo second = callback(order);

            // assert
            assertAll(
                () -> assertThat(second.status()).isEqualTo("PAYMENT_FAILED"),
                () -> assertThat(stock()).isEqualTo(5)
            );
        }
    }

    @DisplayName("조회 회로가 열리면, ")
    @Nested
    class CircuitOpen {
        private static final Duration OPEN_WAIT = Duration.ofMillis(300);

        /** 재조회 없이 표본 2 건 중 실패 50% 이상이면 OPEN 이 되는 짧은 회로 */
        private OrderPaymentFacade facadeWithShortCircuit() {
            TestPgLookupPolicyProperties policy = new TestPgLookupPolicyProperties(1, Duration.ofMillis(10), 2, 2, 2, 50, OPEN_WAIT, 1);
            return new OrderPaymentFacade(orderPaymentSteps,
                TestPgGateways.gateway(pg.baseUrl(), Duration.ofSeconds(1), policy), productListCache, productDetailCache);
        }

        @DisplayName("OPEN 동안은 PG 를 조회하지 않고 확인 불가와 저장된 상태를 돌려주며, 대기 뒤 조회를 재개해 확정한다.")
        @Test
        void keepsStoredStateWhileOpen_andResumesAfterWait() throws InterruptedException {
            // arrange
            OrderPaymentFacade facade = facadeWithShortCircuit();
            Order order = persistDraft(2);
            confirm(order);
            pg.lookupWillRespond(500, "{}");
            facade.reconcile(USER_ID, order.getId());
            facade.reconcile(USER_ID, order.getId());
            pg.lookupNormally();
            pg.complete(onlyTransactionKeyOf(order), "SUCCESS");
            int lookupsWhenOpened = pg.lookupCount();

            // act
            OrderInfo whileOpen = facade.reconcile(USER_ID, order.getId());
            List<String> stateWhileOpen = paymentStateOf(order);
            int lookupsWhileOpen = pg.lookupCount();
            Thread.sleep(OPEN_WAIT.plusMillis(100).toMillis());
            OrderInfo afterWait = facade.reconcile(USER_ID, order.getId());

            // assert
            assertAll(
                () -> assertThat(whileOpen.paymentUnavailable()).isTrue(),
                () -> assertThat(whileOpen.status()).isEqualTo("PAYMENT_PENDING"),
                () -> assertThat(stateWhileOpen).containsExactly("PENDING", "RESERVED", "-"),
                () -> assertThat(lookupsWhileOpen).isEqualTo(lookupsWhenOpened),
                () -> assertThat(afterWait.status()).isEqualTo("CONFIRMED"),
                () -> assertThat(pg.lookupCount()).isEqualTo(lookupsWhenOpened + 1),
                () -> assertThat(stock()).isEqualTo(3),
                () -> assertThat(pg.createCount()).isEqualTo(1)
            );
        }
    }

    @DisplayName("발송 기록 뒤 프로세스가 멈췄다가 다시 뜨면, ")
    @Nested
    class Restart {
        /** 같은 DB · 테스트 PG 에 새 adapter · Facade 를 만들어 재시작을 흉내 냄 */
        private OrderPaymentFacade restartedFacade() {
            return new OrderPaymentFacade(orderPaymentSteps,
                TestPgGateways.gateway(pg.baseUrl(), Duration.ofSeconds(1), TestPgGateways.defaultPolicy()), productListCache, productDetailCache);
        }

        @DisplayName("HTTP 를 보내기 전에 멈췄으면, 시도 · 예약이 REQUESTED · RESERVED 로 남고 조회 0 건이어도 create 를 다시 보내지 않는다.")
        @Test
        void neverResends_whenStoppedBeforeHttp() {
            // arrange: TX A 만 commit 되고 PG 호출 전에 멈춤
            Order order = persistDraft(2);
            orderPaymentSteps.start(USER_ID, order.getId(), "attempt-1");

            // act
            OrderInfo result = restartedFacade().reconcile(USER_ID, order.getId());

            // assert
            assertAll(
                () -> assertThat(result.paymentStatus()).isEqualTo("REQUESTED"),
                () -> assertThat(paymentStateOf(order)).containsExactly("REQUESTED", "RESERVED", "-"),
                () -> assertThat(stock()).isEqualTo(3),
                () -> assertThat(pg.createCount()).isZero()
            );
        }

        @DisplayName("PG 가 거래를 만든 뒤 내부 저장 전에 멈췄으면, 조회로 같은 거래를 찾아 이어 가고 create 를 다시 보내지 않는다.")
        @Test
        void continuesWithSameTransaction_whenStoppedAfterHttp() {
            // arrange: TX A commit → PG 거래 생성 → 생성 응답 반영 전에 멈춤
            Order order = persistDraft(2);
            orderPaymentSteps.start(USER_ID, order.getId(), "attempt-1");
            String transactionKey = pg.addTransaction(providerOrderIdOf(order), 2_000L, "PENDING");

            // act
            OrderInfo pending = restartedFacade().reconcile(USER_ID, order.getId());
            pg.complete(transactionKey, "SUCCESS");
            OrderInfo confirmed = restartedFacade().reconcile(USER_ID, order.getId());

            // assert
            assertAll(
                () -> assertThat(pending.paymentStatus()).isEqualTo("PENDING"),
                () -> assertThat(confirmed.status()).isEqualTo("CONFIRMED"),
                () -> assertThat(stock()).isEqualTo(3),
                () -> assertThat(pg.createCount()).isZero()
            );
        }
    }

    @DisplayName("이전 포인트 결제 주문은, ")
    @Nested
    class PastPointOrder {
        @DisplayName("다시 확정하면 기존 ORDER_ALREADY_CONFIRMED 이고, 결과 확인은 PAYMENT_NOT_FOUND 이며 기존 기록과 PG 는 그대로다.")
        @Test
        void keepsPastRecord() {
            // arrange
            Order order = transactionTemplate.execute(status -> {
                ZonedDateTime now = ZonedDateTime.now();
                Order past = Order.draft(USER_ID, List.of(new OrderLine(product.getId(), product.getName(), product.getPrice(), 1)), now);
                past.confirmWithPoint(past.getTotalAmount(), now);
                entityManager.persist(past);
                return past;
            });

            // act
            CoreException confirmResult = assertThrows(CoreException.class, () -> confirm(order));
            CoreException reconcileResult = assertThrows(CoreException.class, () -> reconcile(order));

            // assert
            assertAll(
                () -> assertThat(confirmResult.getErrorCode()).isEqualTo(OrderErrorCode.ORDER_ALREADY_CONFIRMED),
                () -> assertThat(reconcileResult.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_NOT_FOUND),
                () -> assertThat(orderStatusOf(order)).isEqualTo("CONFIRMED"),
                () -> assertThat(query("select payment_method from orders where id = " + order.getId())).isEqualTo("POINT"),
                () -> assertThat(stock()).isEqualTo(5),
                () -> assertThat(pg.createCount()).isZero()
            );
        }
    }
}
