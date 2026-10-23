package com.loopers.application.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.order.OrderFacade;
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
import com.loopers.domain.point.Point;
import com.loopers.domain.point.PointBalance;
import com.loopers.domain.point.PointRepository;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.user.User;
import com.loopers.domain.user.UserRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.support.testpg.TestPgControl;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// 결제 요청(TX A → PG 생성 → 생성 응답 반영)을 실제 MySQL과 실제 adapter·로컬 테스트 PG로 확인한다.
@SpringBootTest
class PaymentRequestFacadeIntegrationTest {

    private static final long INITIAL_STOCK = 10L;
    private static final int QUANTITY = 2;
    private static final long PRICE = 3_500L;
    private static final long POINT_BALANCE = 100_000L;
    private static final String CARD_TYPE = "TEST";
    private static final String CARD_NO = "0000-0000-0000-0000";

    @Autowired
    private PaymentRequestFacade paymentRequestFacade;

    @Autowired
    private PaymentRequestTransaction paymentRequestTransaction;

    @Autowired
    private PaymentResultService paymentResultService;

    @Autowired
    private OrderFacade orderFacade;

    @MockitoBean
    private ProviderOrderSuffixGenerator suffixGenerator;

    @MockitoSpyBean
    private PaymentGateway paymentGateway;

    @MockitoSpyBean
    private PaymentAttemptRepository paymentAttemptRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private BrandRepository brandRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PointRepository pointRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private String suffix;

    @BeforeEach
    void fixSuffix() {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        when(suffixGenerator.next()).thenAnswer(invocation -> suffix);
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @Nested
    class Start {

        @DisplayName("DRAFT 주문의 결제를 요청하면 재고를 차감하고 PG 접수를 PENDING으로 기록하며 포인트는 쓰지 않는다.")
        @Test
        void reservesStockAndRecordsAcceptance() {
            Fixture fixture = fixture(1);

            PaymentStatusInfo status = request(fixture, "8F14E45F-CEEA-467A-9575-2C9B3E5A1D20");

            assertThat(status.isUnterminated()).isTrue();
            assertThat(status.orderStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
            assertThat(status.paymentMethod()).isEqualTo(PaymentMethod.PG);
            assertThat(status.paymentProgress()).isEqualTo(PaymentProgress.WAITING);
            assertThat(status.attemptStatus()).isEqualTo(PaymentAttemptStatus.PENDING);
            PaymentAttempt attempt = attempt(fixture);
            assertThat(attempt.getPaymentAttemptKey()).isEqualTo("8f14e45f-ceea-467a-9575-2c9b3e5a1d20");
            assertThat(attempt.getProviderOrderId()).isEqualTo(providerOrderId(fixture));
            assertThat(attempt.getAmount()).isEqualTo(PRICE * QUANTITY);
            assertThat(stocks(fixture)).containsExactly(INITIAL_STOCK - QUANTITY);
            assertThat(point(fixture)).isEqualTo(POINT_BALANCE);
            assertThat(TestPgControl.stats(providerOrderId(fixture)).createRequests()).isEqualTo(1);
        }

        @DisplayName("PG가 생성 전에 확정 거절하면 즉시 실패로 종결하고 재고를 반환한다.")
        @Test
        void failsImmediatelyOnRejectionBeforeCreate() {
            Fixture fixture = fixture(1);
            TestPgControl.scenario(providerOrderId(fixture), "{\"scenario\":\"REJECT_BEFORE_CREATE\"}");

            PaymentStatusInfo status = request(fixture, newKey());

            assertThat(status.isUnterminated()).isFalse();
            assertThat(status.orderStatus()).isEqualTo(OrderStatus.PAYMENT_FAILED);
            assertThat(status.paymentProgress()).isEqualTo(PaymentProgress.FAILED);
            assertThat(status.paymentResult()).isEqualTo(PaymentResult.FAILED);
            assertThat(status.attemptStatus()).isEqualTo(PaymentAttemptStatus.FAILED);
            assertThat(stocks(fixture)).containsExactly(INITIAL_STOCK);
            assertThat(TestPgControl.stats(providerOrderId(fixture)).transactions()).isZero();
        }

        @DisplayName("거절 JSON 뒤에 다른 내용이 붙으면 UNKNOWN으로 두고 재고를 유지하며 재요청도 PG 생성을 추가하지 않는다.")
        @ParameterizedTest
        @ValueSource(strings = {" {}", " NOT_JSON"})
        void keepsStockWhenRejectionHasTrailingContent(String trailingContent) throws Exception {
            Fixture fixture = fixture(1);
            String body = "{\"code\":\"TEST_PG_REJECTED_BEFORE_CREATE\",\"orderId\":\"%s\",\"amount\":%d,"
                + "\"transactionCreated\":false}";
            TestPgControl.scenario(providerOrderId(fixture), new ObjectMapper().writeValueAsString(Map.of(
                "scenario", "CREATE_RESPONSE_OVERRIDE", "responseStatus", 422,
                "responseBody", body.formatted(providerOrderId(fixture), PRICE * QUANTITY) + trailingContent,
                "createTransaction", false
            )));
            String key = newKey();

            PaymentStatusInfo first = request(fixture, key);
            PaymentStatusInfo again = request(fixture, key);

            assertThat(first.orderStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
            assertThat(first.attemptStatus()).isEqualTo(PaymentAttemptStatus.UNKNOWN);
            assertThat(first.paymentProgress()).isEqualTo(PaymentProgress.UNCONFIRMED);
            assertThat(again.attemptStatus()).isEqualTo(PaymentAttemptStatus.UNKNOWN);
            assertThat(attempt(fixture).getStatus()).isEqualTo(PaymentAttemptStatus.UNKNOWN);
            assertThat(stocks(fixture)).containsExactly(INITIAL_STOCK - QUANTITY);
            assertThat(TestPgControl.stats(providerOrderId(fixture)).transactions()).isZero();
            assertThat(TestPgControl.stats(providerOrderId(fixture)).createRequests()).isEqualTo(1);
        }

        @DisplayName("PG 생성 요청은 TX A를 commit한 뒤 열린 DB 트랜잭션 없이 보낸다.")
        @Test
        void callsPgOutsideTransactionAfterCommit() {
            Fixture fixture = fixture(1);
            AtomicBoolean transactionActive = new AtomicBoolean(true);
            AtomicReference<String> committedOrderStatus = new AtomicReference<>();
            doAnswer(invocation -> {
                transactionActive.set(TransactionSynchronizationManager.isActualTransactionActive());
                committedOrderStatus.set(jdbcTemplate.queryForObject(
                    "SELECT status FROM orders WHERE id = ?", String.class, fixture.orderId()));
                return invocation.callRealMethod();
            }).when(paymentGateway).create(any());

            request(fixture, newKey());

            assertThat(transactionActive).isFalse();
            assertThat(committedOrderStatus.get()).isEqualTo("PAYMENT_PENDING");
        }

        @DisplayName("생성 응답을 잃으면 결과 미확인으로 응답하고 재고 차감을 유지하며, 같은 키의 재요청은 생성을 다시 보내지 않는다.")
        @Test
        void keepsUnknownAfterDroppedResponse() {
            Fixture fixture = fixture(1);
            TestPgControl.scenario(providerOrderId(fixture), "{\"scenario\":\"DROP_RESPONSE\"}");
            String key = newKey();

            PaymentStatusInfo first = request(fixture, key);
            PaymentStatusInfo again = request(fixture, key);

            assertThat(first.attemptStatus()).isEqualTo(PaymentAttemptStatus.UNKNOWN);
            assertThat(first.paymentProgress()).isEqualTo(PaymentProgress.UNCONFIRMED);
            assertThat(again.attemptStatus()).isEqualTo(PaymentAttemptStatus.UNKNOWN);
            assertThat(stocks(fixture)).containsExactly(INITIAL_STOCK - QUANTITY);
            assertThat(TestPgControl.stats(providerOrderId(fixture)).createRequests()).isEqualTo(1);
        }
    }

    @Nested
    class ItemSnapshot {

        @DisplayName("같은 상품을 나눠 담은 주문은 합산한 수량으로 차감하고, 실패하면 같은 수량을 반환하며 품목 스냅샷은 바뀌지 않는다.")
        @Test
        void usesMergedQuantityForDecreaseAndReturn() {
            Fixture fixture = fixture(2);
            Long first = fixture.productIds().getFirst();
            Long second = fixture.productIds().getLast();
            Long orderId = orderFacade.create(fixture.userId(), List.of(
                new OrderFacade.OrderRequestItem(first, 1),
                new OrderFacade.OrderRequestItem(second, 1),
                new OrderFacade.OrderRequestItem(first, 2)
            )).id();
            Fixture merged = new Fixture(fixture.userId(), orderId, fixture.productIds());
            TestPgControl.scenario(providerOrderId(merged), "{\"scenario\":\"FAIL\"}");

            request(merged, newKey());
            assertThat(stocks(merged)).containsExactly(INITIAL_STOCK - 3, INITIAL_STOCK - 1);
            TestPgControl.complete(providerOrderId(merged));
            paymentResultService.check(attempt(merged).getId());

            assertThat(order(merged).getStatus()).isEqualTo(OrderStatus.PAYMENT_FAILED);
            assertThat(stocks(merged)).containsExactly(INITIAL_STOCK, INITIAL_STOCK);
            assertThat(order(merged).getItems())
                .extracting(OrderItem::getProductId, OrderItem::getQuantity)
                .containsExactlyInAnyOrder(tuple(first, 3), tuple(second, 1));
        }
    }

    @Nested
    class Rejections {

        @DisplayName("시도 키가 없거나 UUID가 아니면 재고 확보 전에 400으로 거절한다.")
        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {" ", "not-a-uuid", "8f14e45f-ceea-467a-9575-2c9b3e5a1d2"})
        void rejectsInvalidAttemptKey(String key) {
            Fixture fixture = fixture(1);

            assertErrorType(() -> paymentRequestFacade.requestPayment(
                fixture.userId(), fixture.orderId(), key, CARD_TYPE, CARD_NO), ErrorType.BAD_REQUEST);

            assertUntouched(fixture);
        }

        @DisplayName("카드 입력이 없거나 공백만이면 재고 확보 전에 400으로 거절한다.")
        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {" "})
        void rejectsMissingCardInput(String value) {
            Fixture fixture = fixture(1);

            assertErrorType(() -> paymentRequestFacade.requestPayment(
                fixture.userId(), fixture.orderId(), newKey(), value, CARD_NO), ErrorType.BAD_REQUEST);
            assertErrorType(() -> paymentRequestFacade.requestPayment(
                fixture.userId(), fixture.orderId(), newKey(), CARD_TYPE, value), ErrorType.BAD_REQUEST);

            assertUntouched(fixture);
        }

        @DisplayName("없는 주문과 타인 주문은 404로 거절한다.")
        @Test
        void rejectsMissingOrOtherUsersOrder() {
            Fixture fixture = fixture(1);
            User other = userRepository.save(User.create());

            assertErrorType(() -> paymentRequestFacade.requestPayment(
                other.getId(), fixture.orderId(), newKey(), CARD_TYPE, CARD_NO), ErrorType.NOT_FOUND);
            assertErrorType(() -> paymentRequestFacade.requestPayment(
                fixture.userId(), Long.MAX_VALUE, newKey(), CARD_TYPE, CARD_NO), ErrorType.NOT_FOUND);

            assertUntouched(fixture);
        }

        @DisplayName("삭제된 상품을 담은 주문은 409로 거절하고 아무것도 바꾸지 않는다.")
        @Test
        void rejectsDeletedProduct() {
            Fixture fixture = fixture(1);
            new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                productRepository.softDeleteActiveById(fixture.productIds().getFirst(), ZonedDateTime.now()));

            assertErrorType(() -> request(fixture, newKey()), ErrorType.CONFLICT);

            assertUntouched(fixture);
        }

        @DisplayName("두 번째 품목의 재고가 부족하면 409로 거절하고 앞서 차감한 품목과 주문 전이도 되돌린다.")
        @Test
        void rollsBackEarlierItemsWhenLaterStockIsShort() {
            Fixture fixture = fixture(2);
            setStock(fixture.productIds().getLast(), 1L);

            assertErrorType(() -> request(fixture, newKey()), ErrorType.CONFLICT);

            assertThat(order(fixture).getStatus()).isEqualTo(OrderStatus.DRAFT);
            assertThat(stocks(fixture)).containsExactly(INITIAL_STOCK, 1L);
            assertThat(attemptCount()).isZero();
            verify(paymentGateway, never()).create(any());
        }

        @DisplayName("이전 포인트로 확정한 주문은 기존 상태 오류 409를 유지한다.")
        @Test
        void rejectsLegacyPointOrder() {
            Fixture fixture = fixture(1);
            jdbcTemplate.update("UPDATE orders SET status = 'CONFIRMED', payment_method = 'POINT', "
                + "payment_amount = total_amount, payment_result = 'SUCCESS' WHERE id = ?", fixture.orderId());

            assertErrorType(() -> request(fixture, newKey()), ErrorType.CONFLICT);

            assertThat(order(fixture).getPaymentMethod()).isEqualTo(PaymentMethod.POINT);
            assertThat(attemptCount()).isZero();
            verify(paymentGateway, never()).create(any());
        }

        @DisplayName("시도 저장이 실패하면 TX A 전체를 되돌리고 PG를 호출하지 않는다.")
        @Test
        void rollsBackWhenAttemptSaveFails() {
            Fixture fixture = fixture(2);
            doAnswer(invocation -> {
                throw new DataAccessResourceFailureException("시도 저장 실패 주입");
            }).when(paymentAttemptRepository).save(any());

            assertThatThrownBy(() -> request(fixture, newKey())).isInstanceOf(DataAccessResourceFailureException.class);

            assertUntouched(fixture);
        }
    }

    @Nested
    class Idempotency {

        @DisplayName("대소문자만 다른 같은 UUID는 같은 요청의 재전송이다.")
        @Test
        void treatsCaseInsensitiveKeyAsSameRequest() {
            Fixture fixture = fixture(1);
            String key = newKey();

            PaymentStatusInfo first = request(fixture, key.toUpperCase(Locale.ROOT));
            PaymentStatusInfo second = request(fixture, key);

            assertThat(second.attemptId()).isEqualTo(first.attemptId());
            assertThat(TestPgControl.stats(providerOrderId(fixture)).createRequests()).isEqualTo(1);
            assertThat(stocks(fixture)).containsExactly(INITIAL_STOCK - QUANTITY);
        }

        @DisplayName("같은 키로 다른 카드가 와도 새 시도·PG 생성 없이 저장된 상태를 응답한다.")
        @Test
        void ignoresDifferentCardForSameKey() {
            Fixture fixture = fixture(1);
            String key = newKey();
            PaymentStatusInfo first = request(fixture, key);

            PaymentStatusInfo replay = paymentRequestFacade.requestPayment(
                fixture.userId(), fixture.orderId(), key, "OTHER", "9999-9999-9999-9999");

            assertThat(replay.attemptId()).isEqualTo(first.attemptId());
            assertThat(replay.attemptStatus()).isEqualTo(PaymentAttemptStatus.PENDING);
            assertThat(TestPgControl.stats(providerOrderId(fixture)).createRequests()).isEqualTo(1);
            assertThat(attemptCount()).isEqualTo(1);
        }

        @DisplayName("다른 주문에 쓴 키는 409이고, 그 주문은 DRAFT와 재고를 유지한다.")
        @Test
        void rejectsKeyUsedForOtherOrder() {
            Fixture first = fixture(1);
            Fixture second = fixture(1);
            String key = newKey();
            request(first, key);

            assertErrorType(() -> request(second, key), ErrorType.CONFLICT);

            assertThat(order(second).getStatus()).isEqualTo(OrderStatus.DRAFT);
            assertThat(stocks(second)).containsExactly(INITIAL_STOCK);
            assertThat(attemptCount()).isEqualTo(1);
        }

        @DisplayName("시도가 있는 주문에 새 키로 요청하면 409이고 두 번째 시도를 만들지 않는다.")
        @Test
        void rejectsNewKeyForStartedOrder() {
            Fixture fixture = fixture(1);
            request(fixture, newKey());

            assertErrorType(() -> request(fixture, newKey()), ErrorType.CONFLICT);

            assertThat(attemptCount()).isEqualTo(1);
            assertThat(TestPgControl.stats(providerOrderId(fixture)).createRequests()).isEqualTo(1);
        }

        @DisplayName("종결된 시도를 같은 키로 다시 요청하면 생성·차감·반환 없이 저장된 결과를 응답한다.")
        @Test
        void returnsStoredTerminalResult() {
            Fixture fixture = fixture(1);
            TestPgControl.scenario(providerOrderId(fixture), "{\"scenario\":\"REJECT_BEFORE_CREATE\"}");
            String key = newKey();
            request(fixture, key);

            PaymentStatusInfo again = request(fixture, key);

            assertThat(again.isUnterminated()).isFalse();
            assertThat(again.orderStatus()).isEqualTo(OrderStatus.PAYMENT_FAILED);
            assertThat(TestPgControl.stats(providerOrderId(fixture)).createRequests()).isEqualTo(1);
            assertThat(stocks(fixture)).containsExactly(INITIAL_STOCK);
        }

        @DisplayName("생성 응답 반영이 실패해도 TX A는 유지되어 저장된 REQUESTED로 응답하고, 재요청은 생성을 다시 보내지 않는다.")
        @Test
        void answersStoredStateWhenResultReflectionFails() {
            Fixture fixture = fixture(1);
            doAnswer(invocation -> {
                throw new DataAccessResourceFailureException("생성 응답 반영 실패 주입");
            }).when(paymentAttemptRepository).markPending(anyLong(), anyString(), any());
            String key = newKey();

            PaymentStatusInfo status = request(fixture, key);
            PaymentStatusInfo again = request(fixture, key);

            assertThat(status.isUnterminated()).isTrue();
            assertThat(status.attemptStatus()).isEqualTo(PaymentAttemptStatus.REQUESTED);
            assertThat(status.paymentProgress()).isEqualTo(PaymentProgress.UNCONFIRMED);
            assertThat(again.attemptStatus()).isEqualTo(PaymentAttemptStatus.REQUESTED);
            assertThat(stocks(fixture)).containsExactly(INITIAL_STOCK - QUANTITY);
            assertThat(TestPgControl.stats(providerOrderId(fixture)).createRequests()).isEqualTo(1);
        }

        @DisplayName("TX A commit 뒤 발송 전에 멈춘 시도는 같은 키로 다시 요청해도 생성을 보내지 않는다.")
        @Test
        void neverSendsAgainForRequestedAttempt() {
            Fixture fixture = fixture(1);
            String key = newKey();
            paymentRequestTransaction.reserve(fixture.userId(), fixture.orderId(), key);

            PaymentStatusInfo again = request(fixture, key);

            assertThat(again.attemptStatus()).isEqualTo(PaymentAttemptStatus.REQUESTED);
            assertThat(again.isUnterminated()).isTrue();
            verify(paymentGateway, never()).create(any());
        }
    }

    @Nested
    class Concurrency {

        @DisplayName("같은 주문·같은 키의 동시 요청은 시도 1건·재고 차감 1회·생성 1회이고, 두 요청 모두 같은 시도를 응답한다.")
        @Test
        void startsOnceForConcurrentSameKey() throws Exception {
            Fixture fixture = fixture(1);
            String key = newKey();

            List<Object> results = runConcurrently(2, index -> request(fixture, key));

            assertThat(results).allSatisfy(result -> assertThat(result).isInstanceOfSatisfying(PaymentStatusInfo.class,
                status -> assertThat(status.orderStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING)));
            assertThat(attemptCount()).isEqualTo(1);
            assertThat(stocks(fixture)).containsExactly(INITIAL_STOCK - QUANTITY);
            assertThat(TestPgControl.stats(providerOrderId(fixture)).createRequests()).isEqualTo(1);
        }

        @DisplayName("다른 주문이 같은 키로 경쟁하면 한쪽만 성공하고, 진 요청은 주문 전이·재고 차감을 되돌린 뒤 409다.")
        @Test
        void allowsOneOrderForConcurrentSameKey() throws Exception {
            Fixture first = fixture(1);
            Fixture second = fixture(1);
            String key = newKey();
            List<Fixture> fixtures = List.of(first, second);

            List<Object> results = runConcurrently(2, index -> request(fixtures.get(index), key));

            assertThat(results).filteredOn(PaymentStatusInfo.class::isInstance).hasSize(1);
            assertThat(results).filteredOn(CoreException.class::isInstance).singleElement()
                .satisfies(error -> assertThat(((CoreException) error).getErrorType()).isEqualTo(ErrorType.CONFLICT));
            assertThat(attemptCount()).isEqualTo(1);
            Fixture loser = results.get(0) instanceof CoreException ? first : second;
            assertThat(order(loser).getStatus()).isEqualTo(OrderStatus.DRAFT);
            assertThat(stocks(loser)).containsExactly(INITIAL_STOCK);
        }
    }

    private List<Object> runConcurrently(int count, Function<Integer, PaymentStatusInfo> task) throws Exception {
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(count);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (int index = 0; index < count; index++) {
                int taskIndex = index;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        return task.apply(taskIndex);
                    } catch (CoreException exception) {
                        return exception;
                    }
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Object> results = new ArrayList<>();
            for (Future<Object> future : futures) {
                results.add(future.get(20, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    private Fixture fixture(int productCount) {
        User user = userRepository.save(User.create());
        pointRepository.save(Point.create(user.getId(), new PointBalance(POINT_BALANCE)));
        Brand brand = brandRepository.save(Brand.create("brand-" + UUID.randomUUID()));
        List<Long> productIds = new ArrayList<>();
        List<OrderItem> items = new ArrayList<>();
        for (int index = 0; index < productCount; index++) {
            Product product = Product.create(brand.getId(), "상품-" + index, PRICE);
            product.changeStockTo(INITIAL_STOCK);
            Product saved = productRepository.save(product);
            productIds.add(saved.getId());
            items.add(OrderItem.create(saved.getId(), saved.getName(), PRICE, QUANTITY));
        }
        Order order = orderRepository.save(Order.create(user.getId(), items));
        return new Fixture(user.getId(), order.getId(), productIds);
    }

    private PaymentStatusInfo request(Fixture fixture, String key) {
        return paymentRequestFacade.requestPayment(fixture.userId(), fixture.orderId(), key, CARD_TYPE, CARD_NO);
    }

    private String providerOrderId(Fixture fixture) {
        return "LP-ORD-%010d-%s".formatted(fixture.orderId(), suffix);
    }

    private static String newKey() {
        return UUID.randomUUID().toString();
    }

    private void setStock(Long productId, long stock) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
            productRepository.updateActiveStock(productId, stock, ZonedDateTime.now()));
    }

    private void assertUntouched(Fixture fixture) {
        assertThat(order(fixture).getStatus()).isEqualTo(OrderStatus.DRAFT);
        assertThat(order(fixture).getPaymentMethod()).isNull();
        assertThat(stocks(fixture)).allSatisfy(stock -> assertThat(stock).isEqualTo(INITIAL_STOCK));
        assertThat(attemptCount()).isZero();
        verify(paymentGateway, never()).create(any());
    }

    private PaymentAttempt attempt(Fixture fixture) {
        return paymentAttemptRepository.findByOrderId(fixture.orderId()).orElseThrow();
    }

    private long attemptCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM payment_attempts", Long.class);
    }

    private Order order(Fixture fixture) {
        return orderRepository.findById(fixture.orderId()).orElseThrow();
    }

    private List<Long> stocks(Fixture fixture) {
        return fixture.productIds().stream()
            .map(productId -> productRepository.findById(productId).orElseThrow().getStock().amount())
            .toList();
    }

    private long point(Fixture fixture) {
        return pointRepository.findByUserId(fixture.userId()).orElseThrow().getBalance().amount();
    }

    private void assertErrorType(Runnable request, ErrorType errorType) {
        assertThatThrownBy(request::run)
            .isInstanceOfSatisfying(CoreException.class, exception ->
                assertThat(exception.getErrorType()).isEqualTo(errorType));
    }

    private record Fixture(Long userId, Long orderId, List<Long> productIds) {}
}
