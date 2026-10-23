package com.loopers.application.order;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.order.PaymentMethod;
import com.loopers.domain.payment.PaymentAttempt;
import com.loopers.domain.payment.PaymentAttemptRepository;
import com.loopers.domain.payment.PaymentAttemptStatus;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.user.UserModel;
import com.loopers.domain.user.UserService;
import com.loopers.infrastructure.order.OrderJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.support.pg.TestPg;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 확정 = PG 결제 시작(docs/week5/design.md 3번). 실제 Facade·MySQL·PG adapter가 테스트 PG(WireMock)와 HTTP로 통신한다.
 * 확인하는 것: TX A 뒤에만 create를 한 번 보낸다(INV-005), 생성 응답별로 결제 시도·주문·재고가 바뀐다(pg-contract.md 2번),
 * 같은 요청의 재시도와 다른 요청을 구분한다(D-015), 포인트는 건드리지 않는다.
 */
@SpringBootTest
class OrderPaymentConfirmTest {

    private static final String CARD_TYPE = "TEST";
    private static final String CARD_NO = "1234-5678-9012-3456";
    private static final long PRICE = 3_500L;
    private static final int INITIAL_STOCK = 10;
    private static final long INITIAL_BALANCE = 50_000L;
    private static final Duration SLOWER_THAN_CREATE_TIMEOUT = Duration.ofMillis(1_000);

    @Autowired
    private OrderFacade orderFacade;

    @Autowired
    private UserService userService;

    @Autowired
    private PaymentAttemptRepository paymentAttemptRepository;

    @Autowired
    private ProductJpaRepository productJpaRepository;

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Autowired
    private OrderJpaRepository orderJpaRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private final WireMockServer pg = TestPg.server();

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private Long userWithBalance() {
        Long userId = userJpaRepository.save(new UserModel()).getId();
        userService.chargePoint(userId, INITIAL_BALANCE);
        return userId;
    }

    private Long product(int stock) {
        return productJpaRepository.save(new ProductModel("상품", PRICE, 1L, stock)).getId();
    }

    private Long draftOrder(Long userId, Long productId, int quantity) {
        return orderFacade.createOrder(userId, List.of(new OrderItemRequest(productId, quantity))).id();
    }

    private static ConfirmPaymentCommand command(String attemptKey) {
        return new ConfirmPaymentCommand(attemptKey, CARD_TYPE, CARD_NO);
    }

    private static String providerOrderIdOf(Long orderId) {
        return String.format("LP-ORD-%010d", orderId);
    }

    private int stockOf(Long productId) {
        return productJpaRepository.findById(productId).orElseThrow().getRemainingStock();
    }

    private long balanceOf(Long userId) {
        return userJpaRepository.findById(userId).orElseThrow().getPoint().getBalance();
    }

    private Order order(Long orderId) {
        return orderJpaRepository.findById(orderId).orElseThrow();
    }

    private PaymentAttempt attemptOf(Long orderId) {
        return paymentAttemptRepository.findByOrderId(orderId).orElseThrow();
    }

    private static RequestPatternBuilder createRequests() {
        return postRequestedFor(urlEqualTo(TestPg.PAYMENTS_PATH));
    }

    /**
     * 응답 지연이 걸린 요청은 지연이 끝나야 WireMock 기록에 남는다 — 기대 수가 보일 때까지 기다리고, 지연만큼 더 지켜본 뒤
     * 정확한 수를 확인한다(늦게 기록되는 숨은 재발송까지 잡기 위해서다).
     */
    private void verifyCreatesAfterDelay(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (pg.findAll(createRequests()).size() < expected && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(20);
        }
        TimeUnit.MILLISECONDS.sleep(SLOWER_THAN_CREATE_TIMEOUT.toMillis());
        pg.verify(exactly(expected), createRequests());
    }

    private void stubCreateTimesOut() {
        pg.stubFor(post(urlEqualTo(TestPg.PAYMENTS_PATH))
            .willReturn(aResponse().withStatus(202).withFixedDelay((int) SLOWER_THAN_CREATE_TIMEOUT.toMillis())));
    }

    private void stubListLookup(Long orderId, String body) {
        pg.stubFor(get(urlPathEqualTo(TestPg.PAYMENTS_PATH))
            .withQueryParam("orderId", equalTo(providerOrderIdOf(orderId)))
            .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body)));
    }

    private static String transactionJson(Long orderId, long amount, String status) {
        return "{\"transactionKey\":\"TEST-TX-LOST\",\"orderId\":\"" + providerOrderIdOf(orderId)
            + "\",\"amount\":" + amount + ",\"status\":\"" + status + "\"}";
    }

    @DisplayName("PG가 생성 요청을 202 PENDING으로 받으면, ")
    @Nested
    class Accepted {
        @DisplayName("재고 확보와 결제 시도 기록 뒤 create를 한 번 보내고, 주문은 PAYMENT_PENDING·시도는 처리중이며 포인트는 그대로다.")
        @Test
        void reservesStock_sendsOnce_andLeavesPaymentPending() {
            // arrange
            Long userId = userWithBalance();
            Long productId = product(INITIAL_STOCK);
            Long orderId = draftOrder(userId, productId, 2);

            // act
            OrderInfo info = orderFacade.confirmOrder(orderId, userId, command("key-1"));

            // assert
            PaymentAttempt attempt = attemptOf(orderId);
            assertAll(
                () -> assertThat(info.status()).isEqualTo(OrderStatus.PAYMENT_PENDING),
                () -> assertThat(info.paymentMethod()).isEqualTo(PaymentMethod.PG),
                () -> assertThat(info.payment().status()).isEqualTo(PaymentAttemptStatus.PENDING),
                () -> assertThat(info.payment().amount()).isEqualTo(PRICE * 2),
                () -> assertThat(attempt.getTransactionKey()).isEqualTo(TestPg.transactionKeyOf(providerOrderIdOf(orderId))),
                () -> assertThat(stockOf(productId)).isEqualTo(INITIAL_STOCK - 2),
                () -> assertThat(balanceOf(userId)).isEqualTo(INITIAL_BALANCE),
                () -> assertThat(order(orderId).getPaidAmount()).isNull()
            );
            pg.verify(exactly(1), createRequests()
                .withHeader("X-USER-ID", equalTo("135135"))
                .withRequestBody(matchingJsonPath("$.orderId", equalTo(providerOrderIdOf(orderId))))
                .withRequestBody(matchingJsonPath("$.amount", equalTo(String.valueOf(PRICE * 2))))
                .withRequestBody(matchingJsonPath("$.cardNo", equalTo(CARD_NO))));
        }
    }

    @DisplayName("PG가 효과 없는 거절(정확한 조건의 422)을 돌려주면, ")
    @Nested
    class RejectedBeforeCreate {
        @DisplayName("결제 시도는 FAILED, 주문은 PAYMENT_FAILED가 되고 확보했던 재고를 한 번 돌려준다.")
        @Test
        void failsPayment_andReturnsReservedStock() {
            // arrange
            Long userId = userWithBalance();
            Long productId = product(INITIAL_STOCK);
            Long orderId = draftOrder(userId, productId, 3);
            pg.stubFor(post(urlEqualTo(TestPg.PAYMENTS_PATH))
                .willReturn(aResponse().withStatus(422).withHeader("Content-Type", "application/json")
                    .withBody("{\"code\":\"TEST_PG_REJECTED_BEFORE_CREATE\",\"orderId\":\"" + providerOrderIdOf(orderId)
                        + "\",\"amount\":" + PRICE * 3 + ",\"transactionCreated\":false}")));

            // act
            OrderInfo info = orderFacade.confirmOrder(orderId, userId, command("key-1"));

            // assert
            assertAll(
                () -> assertThat(info.status()).isEqualTo(OrderStatus.PAYMENT_FAILED),
                () -> assertThat(info.payment().status()).isEqualTo(PaymentAttemptStatus.FAILED),
                () -> assertThat(attemptOf(orderId).getTransactionKey()).isNull(),
                () -> assertThat(stockOf(productId)).isEqualTo(INITIAL_STOCK),
                () -> assertThat(balanceOf(userId)).isEqualTo(INITIAL_BALANCE)
            );
        }

        @DisplayName("본문이 조건에 맞지 않는 422는 거절로 보지 않고 결과 미확인으로 두며 재고를 유지한다.")
        @Test
        void keepsReservation_whenRejectionIsNotExact() {
            // arrange
            Long userId = userWithBalance();
            Long productId = product(INITIAL_STOCK);
            Long orderId = draftOrder(userId, productId, 3);
            pg.stubFor(post(urlEqualTo(TestPg.PAYMENTS_PATH)).willReturn(aResponse().withStatus(422)));
            stubListLookup(orderId, "[]");

            // act
            OrderInfo info = orderFacade.confirmOrder(orderId, userId, command("key-1"));

            // assert
            assertAll(
                () -> assertThat(info.status()).isEqualTo(OrderStatus.PAYMENT_PENDING),
                () -> assertThat(info.payment().status()).isEqualTo(PaymentAttemptStatus.UNKNOWN),
                () -> assertThat(stockOf(productId)).isEqualTo(INITIAL_STOCK - 3)
            );
        }
    }

    @DisplayName("생성 응답을 받지 못하면(timeout), ")
    @Nested
    class ResponseLost {
        @DisplayName("바로 재조회한 목록에 SUCCESS 거래가 한 건 있으면 그 거래로 확정하고, create는 다시 보내지 않는다.")
        @Test
        void confirmsByImmediateLookup_withoutResending() throws InterruptedException {
            // arrange
            Long userId = userWithBalance();
            Long productId = product(INITIAL_STOCK);
            Long orderId = draftOrder(userId, productId, 2);
            stubCreateTimesOut();
            stubListLookup(orderId, "[" + transactionJson(orderId, PRICE * 2, "SUCCESS") + "]");

            // act
            OrderInfo info = orderFacade.confirmOrder(orderId, userId, command("key-1"));

            // assert
            assertAll(
                () -> assertThat(info.status()).isEqualTo(OrderStatus.CONFIRMED),
                () -> assertThat(info.payment().status()).isEqualTo(PaymentAttemptStatus.SUCCESS),
                () -> assertThat(info.paidAmount()).isEqualTo(PRICE * 2),
                () -> assertThat(attemptOf(orderId).getTransactionKey()).isEqualTo("TEST-TX-LOST"),
                () -> assertThat(stockOf(productId)).isEqualTo(INITIAL_STOCK - 2),
                () -> assertThat(balanceOf(userId)).isEqualTo(INITIAL_BALANCE)
            );
            verifyCreatesAfterDelay(1);
        }

        @DisplayName("재조회 결과가 0건이면 실패가 아니라 결과 미확인(UNKNOWN)으로 두고 재고를 유지한다.")
        @Test
        void staysUnknown_whenLookupFindsNothing() throws InterruptedException {
            // arrange
            Long userId = userWithBalance();
            Long productId = product(INITIAL_STOCK);
            Long orderId = draftOrder(userId, productId, 2);
            stubCreateTimesOut();
            stubListLookup(orderId, "[]");

            // act
            OrderInfo info = orderFacade.confirmOrder(orderId, userId, command("key-1"));

            // assert
            assertAll(
                () -> assertThat(info.status()).isEqualTo(OrderStatus.PAYMENT_PENDING),
                () -> assertThat(info.payment().status()).isEqualTo(PaymentAttemptStatus.UNKNOWN),
                () -> assertThat(stockOf(productId)).isEqualTo(INITIAL_STOCK - 2)
            );
            verifyCreatesAfterDelay(1);
        }

        @DisplayName("재조회마저 실패하면 결과 미확인으로 두고, 재조회는 정한 횟수(2회)까지만 한다.")
        @Test
        void staysUnknown_whenLookupFails() throws InterruptedException {
            // arrange
            Long userId = userWithBalance();
            Long productId = product(INITIAL_STOCK);
            Long orderId = draftOrder(userId, productId, 2);
            stubCreateTimesOut();
            pg.stubFor(get(urlPathEqualTo(TestPg.PAYMENTS_PATH)).willReturn(aResponse().withStatus(500)));

            // act
            OrderInfo info = orderFacade.confirmOrder(orderId, userId, command("key-1"));

            // assert
            assertThat(info.payment().status()).isEqualTo(PaymentAttemptStatus.UNKNOWN);
            pg.verify(exactly(2), getRequestedFor(urlPathEqualTo(TestPg.PAYMENTS_PATH)));
            verifyCreatesAfterDelay(1);
        }
    }

    @DisplayName("PG를 부르기 전에 거절되면, ")
    @Nested
    class RejectedBeforeSending {
        @DisplayName("재고가 부족하면 400이고, 주문은 DRAFT·재고는 그대로이며 결제 시도와 create가 없다.")
        @Test
        void rollsBack_withoutCallingPg_whenOutOfStock() {
            // arrange
            Long userId = userWithBalance();
            Long productId = product(1);
            Long orderId = draftOrder(userId, productId, 2);

            // act
            CoreException exception = assertThrows(CoreException.class,
                () -> orderFacade.confirmOrder(orderId, userId, command("key-1")));

            // assert
            assertAll(
                () -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST),
                () -> assertThat(order(orderId).getStatus()).isEqualTo(OrderStatus.DRAFT),
                () -> assertThat(stockOf(productId)).isEqualTo(1),
                () -> assertThat(paymentAttemptRepository.findByOrderId(orderId)).isEmpty()
            );
            pg.verify(exactly(0), createRequests());
        }

        @DisplayName("결제 시도 키나 카드 값이 비어 있으면 400이다.")
        @Test
        void rejectsBlankInput() {
            CoreException exception = assertThrows(CoreException.class,
                () -> new ConfirmPaymentCommand(" ", CARD_TYPE, CARD_NO));
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }
    }

    @DisplayName("이미 결제 시도가 있는 주문에 다시 요청하면 (D-015), ")
    @Nested
    class RepeatedRequest {
        @DisplayName("같은 키면 PG를 다시 부르지 않고 저장된 상태를 돌려준다.")
        @Test
        void returnsStoredState_forSameKey() {
            // arrange
            Long userId = userWithBalance();
            Long productId = product(INITIAL_STOCK);
            Long orderId = draftOrder(userId, productId, 2);
            orderFacade.confirmOrder(orderId, userId, command("key-1"));

            // act
            OrderInfo again = orderFacade.confirmOrder(orderId, userId, command("key-1"));

            // assert
            assertAll(
                () -> assertThat(again.status()).isEqualTo(OrderStatus.PAYMENT_PENDING),
                () -> assertThat(again.payment().attemptKey()).isEqualTo("key-1"),
                () -> assertThat(stockOf(productId)).isEqualTo(INITIAL_STOCK - 2)
            );
            pg.verify(exactly(1), createRequests());
        }

        @DisplayName("다른 키면 409이고, 재고와 PG 요청 수는 그대로다.")
        @Test
        void conflicts_forDifferentKey() {
            // arrange
            Long userId = userWithBalance();
            Long productId = product(INITIAL_STOCK);
            Long orderId = draftOrder(userId, productId, 2);
            orderFacade.confirmOrder(orderId, userId, command("key-1"));

            // act
            CoreException exception = assertThrows(CoreException.class,
                () -> orderFacade.confirmOrder(orderId, userId, command("key-2")));

            // assert
            assertAll(
                () -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.CONFLICT),
                () -> assertThat(stockOf(productId)).isEqualTo(INITIAL_STOCK - 2)
            );
            pg.verify(exactly(1), createRequests());
        }

        @DisplayName("같은 키를 다른 주문에 쓰면 400이고, 그 주문은 DRAFT로 남는다.")
        @Test
        void rejects_sameKeyForAnotherOrder() {
            // arrange
            Long userId = userWithBalance();
            Long productId = product(INITIAL_STOCK);
            Long firstOrderId = draftOrder(userId, productId, 1);
            Long secondOrderId = draftOrder(userId, productId, 1);
            orderFacade.confirmOrder(firstOrderId, userId, command("key-1"));

            // act
            CoreException exception = assertThrows(CoreException.class,
                () -> orderFacade.confirmOrder(secondOrderId, userId, command("key-1")));

            // assert
            assertAll(
                () -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST),
                () -> assertThat(order(secondOrderId).getStatus()).isEqualTo(OrderStatus.DRAFT),
                () -> assertThat(stockOf(productId)).isEqualTo(INITIAL_STOCK - 1)
            );
            pg.verify(exactly(1), createRequests());
        }

        @DisplayName("이전에 포인트로 확정된 주문은 기존과 같이 409다.")
        @Test
        void conflicts_forPointConfirmedOrder() {
            // arrange
            Long userId = userWithBalance();
            Long productId = product(INITIAL_STOCK);
            Long orderId = draftOrder(userId, productId, 1);
            Order order = order(orderId);
            order.confirm(PRICE);
            orderJpaRepository.save(order);

            // act
            CoreException exception = assertThrows(CoreException.class,
                () -> orderFacade.confirmOrder(orderId, userId, command("key-1")));

            // assert
            assertAll(
                () -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.CONFLICT),
                () -> assertThat(order(orderId).getPaymentMethod()).isEqualTo(PaymentMethod.POINT)
            );
            pg.verify(exactly(0), createRequests());
        }
    }

    @DisplayName("같은 주문에 확정 요청이 동시에 들어오면, ")
    @Nested
    class Concurrent {
        @DisplayName("키가 같든 다르든 결제 시도·재고 확보·create는 각각 한 번뿐이다.")
        @Test
        void startsOnlyOnce() throws Exception {
            // arrange
            Long userId = userWithBalance();
            Long productId = product(INITIAL_STOCK);
            Long orderId = draftOrder(userId, productId, 2);
            List<String> keys = List.of("key-1", "key-1", "key-2", "key-3");

            // act
            List<Callable<OrderInfo>> requests = new ArrayList<>();
            for (String key : keys) {
                requests.add(() -> orderFacade.confirmOrder(orderId, userId, command(key)));
            }
            List<Throwable> failures = runConcurrently(requests);

            // assert — 이긴 키가 무엇이든: 같은 키 요청은 저장된 상태, 다른 키 요청은 409
            String winner = attemptOf(orderId).getAttemptKey();
            long expectedConflicts = keys.stream().filter(key -> !key.equals(winner)).count();
            assertAll(
                () -> assertThat(failures).hasSize((int) expectedConflicts),
                () -> assertThat(failures).allSatisfy(e -> assertThat(e).isInstanceOfSatisfying(CoreException.class,
                    core -> assertThat(core.getErrorType()).isEqualTo(ErrorType.CONFLICT))),
                () -> assertThat(stockOf(productId)).isEqualTo(INITIAL_STOCK - 2),
                () -> assertThat(order(orderId).getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING)
            );
            pg.verify(exactly(1), createRequests());
        }
    }

    @DisplayName("결제 시도 테이블에는 카드 번호를 담는 컬럼이 없다.")
    @Test
    void doesNotStoreCardNumber() {
        @SuppressWarnings("unchecked")
        List<String> columns = entityManager.createNativeQuery(
                "SELECT column_name FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'payment_attempt'")
            .getResultList();
        assertThat(columns).isNotEmpty().noneMatch(column -> column.toLowerCase().contains("card"));
    }

    private static <T> List<Throwable> runConcurrently(List<Callable<T>> requests) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(requests.size());
        CountDownLatch ready = new CountDownLatch(requests.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Throwable>> futures = new ArrayList<>();
            for (Callable<T> request : requests) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await(30, TimeUnit.SECONDS);
                    try {
                        request.call();
                        return null;
                    } catch (Exception e) {
                        return e;
                    }
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Throwable> failures = new ArrayList<>();
            for (Future<Throwable> future : futures) {
                Throwable failure = future.get(30, TimeUnit.SECONDS);
                if (failure != null) {
                    failures.add(failure);
                }
            }
            return failures;
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }
}
