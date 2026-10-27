package com.loopers.application.order;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.loopers.domain.order.Order;
import com.loopers.domain.payment.PaymentAttempt;
import com.loopers.domain.payment.PaymentFinalResult;
import com.loopers.domain.payment.PaymentResultService;
import com.loopers.domain.point.Point;
import com.loopers.domain.product.Price;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.StockReservation;
import com.loopers.domain.user.User;
import com.loopers.infrastructure.order.OrderJpaRepository;
import com.loopers.infrastructure.payment.FakePaymentGateway;
import com.loopers.infrastructure.payment.FakePaymentGatewayConfig;
import com.loopers.infrastructure.payment.PaymentAttemptJpaRepository;
import com.loopers.infrastructure.point.PointJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.infrastructure.product.StockReservationJpaRepository;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.utils.ConcurrentRunner;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 주문 확정(PG 결제 시작)을 실제 MySQL 과 가짜 PG 로 검증한다.
 * TX A(확보·시도 저장) → PG create → TX B(결과 반영)가 Facade 와 PaymentResultService 를 통해 어떻게 이어지는지 본다.
 * 클래스 단위 @Transactional 을 쓰지 않아 각 TX 가 실제로 commit/rollback 된다.
 */
@SpringBootTest
@Import(FakePaymentGatewayConfig.class)
class OrderPaymentIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final String CARD_TYPE = "TEST";
    private static final String CARD_NO = "0000-0000-0000-0000";

    private final OrderFacade orderFacade;
    private final PaymentResultService paymentResultService;
    private final FakePaymentGateway fakePaymentGateway;
    private final UserJpaRepository userJpaRepository;
    private final ProductJpaRepository productJpaRepository;
    private final PointJpaRepository pointJpaRepository;
    private final OrderJpaRepository orderJpaRepository;
    private final PaymentAttemptJpaRepository paymentAttemptJpaRepository;
    private final StockReservationJpaRepository stockReservationJpaRepository;
    private final JdbcTemplate jdbcTemplate;
    private final DatabaseCleanUp databaseCleanUp;

    private ListAppender<ILoggingEvent> logs;

    @Autowired
    public OrderPaymentIntegrationTest(
        OrderFacade orderFacade,
        PaymentResultService paymentResultService,
        FakePaymentGateway fakePaymentGateway,
        UserJpaRepository userJpaRepository,
        ProductJpaRepository productJpaRepository,
        PointJpaRepository pointJpaRepository,
        OrderJpaRepository orderJpaRepository,
        PaymentAttemptJpaRepository paymentAttemptJpaRepository,
        StockReservationJpaRepository stockReservationJpaRepository,
        JdbcTemplate jdbcTemplate,
        DatabaseCleanUp databaseCleanUp
    ) {
        this.orderFacade = orderFacade;
        this.paymentResultService = paymentResultService;
        this.fakePaymentGateway = fakePaymentGateway;
        this.userJpaRepository = userJpaRepository;
        this.productJpaRepository = productJpaRepository;
        this.pointJpaRepository = pointJpaRepository;
        this.orderJpaRepository = orderJpaRepository;
        this.paymentAttemptJpaRepository = paymentAttemptJpaRepository;
        this.stockReservationJpaRepository = stockReservationJpaRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.databaseCleanUp = databaseCleanUp;
    }

    @BeforeEach
    void setUp() {
        fakePaymentGateway.reset();
        logs = new ListAppender<>();
        logs.start();
        appLogger().addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        appLogger().detachAppender(logs);
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("DRAFT 주문을 처음 확정할 때, ")
    @Nested
    class FirstConfirm {
        @DisplayName("create 가 1회 호출되고 시도는 PENDING + key 로 저장되며, TX B 에 SUCCESS 를 적용하면 주문 CONFIRMED·결제수단 PG·재고 5→3 유지·예약 CONSUMED·포인트 변화 0 이다.")
        @Test
        void startsPgPaymentAndSettlesOnSuccess() {
            // arrange
            Long userId = saveUser();
            savePoint(userId, 5000L);
            Long productId = saveProduct("상품", 1000L, 5);
            Long orderId = createOrder(userId, productId, 2);

            // act 1 - TX A + create + 생성 수락 기록
            OrderConfirmInfo info = confirm(userId, orderId, "k1");

            // assert 1
            assertThat(fakePaymentGateway.createCount()).isEqualTo(1);
            PaymentAttempt attempt = attemptOf(orderId);
            assertThat(attempt.getStatus()).isEqualTo(PaymentAttempt.Status.PENDING);
            assertThat(attempt.getTransactionKey()).isNotNull();
            assertThat(info.attemptStatus()).isEqualTo("PENDING");
            assertThat(info.settled()).isFalse();
            assertThat(orderOf(orderId).getStatus()).isEqualTo(Order.OrderStatus.PAYMENT_PENDING);
            assertThat(orderOf(orderId).getPaymentMethod()).isEqualTo(Order.PaymentMethod.PG);
            assertThat(stockOf(productId)).isEqualTo(3);
            assertThat(reservationsOf(orderId)).singleElement()
                .extracting(StockReservation::getStatus).isEqualTo(StockReservation.Status.RESERVED);

            // act 2 - PG 가 성공으로 바뀐 결과를 TX B 에 적용
            fakePaymentGateway.markSuccess(attempt.getTransactionKey());
            boolean applied = paymentResultService.applyFinal(
                attempt.getId(), PaymentFinalResult.success(attempt.getTransactionKey()));

            // assert 2
            assertThat(applied).isTrue();
            Order order = orderOf(orderId);
            assertThat(order.getStatus()).isEqualTo(Order.OrderStatus.CONFIRMED);
            assertThat(order.getPaidAmount()).isEqualTo(2000L);
            assertThat(order.getPaymentMethod()).isEqualTo(Order.PaymentMethod.PG);
            assertThat(attemptOf(orderId).getStatus()).isEqualTo(PaymentAttempt.Status.SUCCESS);
            assertThat(reservationsOf(orderId)).singleElement()
                .extracting(StockReservation::getStatus).isEqualTo(StockReservation.Status.CONSUMED);
            assertThat(stockOf(productId)).isEqualTo(3);
            assertThat(balanceOf(userId)).isEqualTo(5000L);
            assertThat(fakePaymentGateway.createCount()).isEqualTo(1);
        }

        @DisplayName("재고가 부족하면, create 0회·주문 DRAFT 유지·재고·예약·시도 변화 없이 재고 부족으로 거절된다.")
        @Test
        void rejectsWithoutPgCall_whenStockIsInsufficient() {
            // arrange
            Long userId = saveUser();
            Long productId = saveProduct("상품", 1000L, 1);
            Long orderId = createOrder(userId, productId, 2);

            // act
            CoreException result = assertThrows(CoreException.class, () -> confirm(userId, orderId, "k1"));

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.CONFLICT);
            assertThat(result.getMessage()).isEqualTo("재고가 부족합니다.");
            assertThat(orderOf(orderId).getStatus()).isEqualTo(Order.OrderStatus.DRAFT);
            assertThat(orderOf(orderId).getPaymentMethod()).isNull();
            assertThat(stockOf(productId)).isEqualTo(1);
            assertThat(paymentAttemptJpaRepository.count()).isZero();
            assertThat(reservationsOf(orderId)).isEmpty();
            assertThat(fakePaymentGateway.createCount()).isZero();
        }

        @DisplayName("같은 키로 동시에 8번 확정하면, 결제 시도 1개·예약 1벌·create 1회이고 재고는 3 이다.")
        @Test
        void createsOneAttemptAndCallsPgOnce_whenSameKeyConfirmsConcurrently() throws InterruptedException {
            // arrange
            Long userId = saveUser();
            Long productId = saveProduct("상품", 1000L, 5);
            Long orderId = createOrder(userId, productId, 2);
            List<Callable<OrderConfirmInfo>> tasks = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                tasks.add(() -> confirm(userId, orderId, "k1"));
            }

            // act
            List<ConcurrentRunner.Run<OrderConfirmInfo>> runs = ConcurrentRunner.runAll(tasks, TIMEOUT);

            // assert - 같은 키·같은 요청의 재요청은 오류가 아니라 저장 상태를 돌려받는다
            List<Throwable> errors = runs.stream().filter(ConcurrentRunner.Run::failed).map(ConcurrentRunner.Run::error).toList();
            assertThat(errors).withFailMessage("오류 %d건: %s", errors.size(), errors).isEmpty();
            assertThat(paymentAttemptJpaRepository.count()).isEqualTo(1);
            assertThat(reservationsOf(orderId)).singleElement()
                .extracting(StockReservation::getStatus).isEqualTo(StockReservation.Status.RESERVED);
            assertThat(fakePaymentGateway.createCount()).isEqualTo(1);
            assertThat(stockOf(productId)).isEqualTo(3);
            assertThat(orderOf(orderId).getStatus()).isEqualTo(Order.OrderStatus.PAYMENT_PENDING);
            assertThat(attemptOf(orderId).getStatus()).isEqualTo(PaymentAttempt.Status.PENDING);
        }
    }

    @DisplayName("합성 카드 입력이 아닐 때, ")
    @Nested
    class CardInput {
        @DisplayName("cardType 이 TEST 가 아니면, 400 으로 거절되고 시도·예약은 0건, create 는 0회다.")
        @Test
        void rejectsBadRequest_whenCardTypeIsNotTest() {
            // arrange
            Long userId = saveUser();
            Long productId = saveProduct("상품", 1000L, 5);
            Long orderId = createOrder(userId, productId, 2);

            // act
            CoreException result = assertThrows(CoreException.class,
                () -> confirm(userId, orderId, "k1", "VISA", CARD_NO));

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
            assertNothingStarted(orderId, productId);
        }

        @DisplayName("카드 번호가 허용된 테스트 번호(0000-0000-0000-0000)가 아니면, 400 으로 거절되고 시도·예약은 0건, create 는 0회다.")
        @Test
        void rejectsBadRequest_whenCardNumberIsNotAllowedTestNumber() {
            // arrange
            Long userId = saveUser();
            Long productId = saveProduct("상품", 1000L, 5);
            Long orderId = createOrder(userId, productId, 2);

            // act
            CoreException result = assertThrows(CoreException.class,
                () -> confirm(userId, orderId, "k1", CARD_TYPE, "1111-1111-1111-1111"));

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
            assertNothingStarted(orderId, productId);
        }

        private void assertNothingStarted(Long orderId, Long productId) {
            assertThat(orderOf(orderId).getStatus()).isEqualTo(Order.OrderStatus.DRAFT);
            assertThat(orderOf(orderId).getPaymentMethod()).isNull();
            assertThat(stockOf(productId)).isEqualTo(5);
            assertThat(paymentAttemptJpaRepository.count()).isZero();
            assertThat(reservationsOf(orderId)).isEmpty();
            assertThat(fakePaymentGateway.createCount()).isZero();
        }
    }

    @DisplayName("이미 시도가 있는 주문에 다시 확정을 요청할 때, ")
    @Nested
    class Reconfirm {
        @DisplayName("다른 키로 요청하면, 충돌로 거절되고 create 는 추가로 호출되지 않는다.")
        @Test
        void rejectsConflict_whenDifferentKeyIsUsedForSameOrder() {
            // arrange
            Long userId = saveUser();
            Long productId = saveProduct("상품", 1000L, 5);
            Long orderId = createOrder(userId, productId, 2);
            confirm(userId, orderId, "k1");

            // act
            CoreException result = assertThrows(CoreException.class, () -> confirm(userId, orderId, "k2"));

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.CONFLICT);
            assertThat(fakePaymentGateway.createCount()).isEqualTo(1);
            assertThat(paymentAttemptJpaRepository.count()).isEqualTo(1);
            assertThat(attemptOf(orderId).getAttemptKey()).isEqualTo("k1");
            assertThat(stockOf(productId)).isEqualTo(3);
        }

        /**
         * 카드 허용 목록이 cardType TEST 하나뿐이라 "cardType 이 다른 요청"은 충돌 비교 전에 400 으로 끝난다.
         * 그래서 "같은 키 + 다른 요청"은 같은 사용자의 다른 주문(금액이 다름)으로 만든다 (설계 4-1 "같은 키의 다른 주문·금액·요청").
         */
        @DisplayName("같은 키를 다른 주문에 쓰면, 충돌로 거절되고 그 주문은 DRAFT 그대로이며 create 는 추가로 호출되지 않는다.")
        @Test
        void rejectsConflict_whenSameKeyIsUsedForDifferentRequest() {
            // arrange
            Long userId = saveUser();
            Long firstProductId = saveProduct("첫 상품", 1000L, 5);
            Long secondProductId = saveProduct("둘째 상품", 500L, 5);
            Long firstOrderId = createOrder(userId, firstProductId, 2);
            Long secondOrderId = createOrder(userId, secondProductId, 1);
            confirm(userId, firstOrderId, "k1");

            // act
            CoreException result = assertThrows(CoreException.class, () -> confirm(userId, secondOrderId, "k1"));

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.CONFLICT);
            assertThat(orderOf(secondOrderId).getStatus()).isEqualTo(Order.OrderStatus.DRAFT);
            assertThat(stockOf(secondProductId)).isEqualTo(5);
            assertThat(paymentAttemptJpaRepository.findByOrderId(secondOrderId)).isEmpty();
            assertThat(reservationsOf(secondOrderId)).isEmpty();
            assertThat(paymentAttemptJpaRepository.count()).isEqualTo(1);
            assertThat(fakePaymentGateway.createCount()).isEqualTo(1);
        }
    }

    @DisplayName("create 응답이 정상 수락이 아닐 때, ")
    @Nested
    class CreateOutcome {
        @DisplayName("거래는 생겼지만 응답이 유실되면, 시도 UNKNOWN·예약 유지·재고 3 이고, 같은 키 재요청에도 create 는 추가 0회·가짜 PG 거래는 1건이다.")
        @Test
        void keepsReservationAsUnknown_whenCreateResponseIsLost() {
            // arrange
            fakePaymentGateway.willCreateButLoseResponse();
            Long userId = saveUser();
            Long productId = saveProduct("상품", 1000L, 5);
            Long orderId = createOrder(userId, productId, 2);

            // act
            OrderConfirmInfo first = confirm(userId, orderId, "k1");
            OrderConfirmInfo again = confirm(userId, orderId, "k1");

            // assert
            assertThat(first.attemptStatus()).isEqualTo("UNKNOWN");
            assertThat(first.settled()).isFalse();
            assertThat(again.attemptStatus()).isEqualTo("UNKNOWN");
            PaymentAttempt attempt = attemptOf(orderId);
            assertThat(attempt.getStatus()).isEqualTo(PaymentAttempt.Status.UNKNOWN);
            assertThat(attempt.getTransactionKey()).isNull();
            assertThat(orderOf(orderId).getStatus()).isEqualTo(Order.OrderStatus.PAYMENT_PENDING);
            assertThat(reservationsOf(orderId)).singleElement()
                .extracting(StockReservation::getStatus).isEqualTo(StockReservation.Status.RESERVED);
            assertThat(stockOf(productId)).isEqualTo(3);
            assertThat(fakePaymentGateway.transactions()).hasSize(1);
            assertThat(fakePaymentGateway.createCount()).isEqualTo(1);
        }

        @DisplayName("효과 0건 확정 거절(422)이면, 가짜 PG 거래 0건·시도 FAILED(key 없음, 사유 REJECTED_BEFORE_CREATE)·주문 PAYMENT_FAILED·재고 5·예약 RELEASED 다.")
        @Test
        void failsAndReleasesOnce_whenCreateIsRejectedBeforeCreate() {
            // arrange
            fakePaymentGateway.willRejectNoEffect();
            Long userId = saveUser();
            Long productId = saveProduct("상품", 1000L, 5);
            Long orderId = createOrder(userId, productId, 2);

            // act
            OrderConfirmInfo info = confirm(userId, orderId, "k1");

            // assert
            assertThat(info.settled()).isTrue();
            assertThat(info.attemptStatus()).isEqualTo("FAILED");
            PaymentAttempt attempt = attemptOf(orderId);
            assertThat(attempt.getStatus()).isEqualTo(PaymentAttempt.Status.FAILED);
            assertThat(attempt.getFailureReason()).isEqualTo(PaymentAttempt.FailureReason.REJECTED_BEFORE_CREATE);
            assertThat(attempt.getTransactionKey()).isNull();
            assertThat(orderOf(orderId).getStatus()).isEqualTo(Order.OrderStatus.PAYMENT_FAILED);
            assertThat(stockOf(productId)).isEqualTo(5);
            assertThat(reservationsOf(orderId)).singleElement()
                .extracting(StockReservation::getStatus).isEqualTo(StockReservation.Status.RELEASED);
            assertThat(fakePaymentGateway.transactions()).isEmpty();
            assertThat(fakePaymentGateway.createCount()).isEqualTo(1);
        }

        @DisplayName("202 인데 본문 금액이 요청과 다르면, 시도 UNKNOWN + 충돌(AMOUNT_MISMATCH)·key 없음·주문 PAYMENT_PENDING·예약 RESERVED·재고 3 이고 create 는 1회다 (결정 ㉕).")
        @Test
        void keepsUnknownWithConflict_whenAcceptedResponseAmountDiffers() {
            // arrange
            fakePaymentGateway.willAcceptWithMismatchedAmount();
            Long userId = saveUser();
            Long productId = saveProduct("상품", 1000L, 5);
            Long orderId = createOrder(userId, productId, 2);

            // act
            OrderConfirmInfo info = confirm(userId, orderId, "k1");

            // assert
            assertThat(info.settled()).isFalse();
            assertThat(info.attemptStatus()).isEqualTo("UNKNOWN");
            assertUnknownWithConflict(orderId, productId, PaymentAttempt.ConflictReason.AMOUNT_MISMATCH);
            assertThat(fakePaymentGateway.transactions()).hasSize(1);
            assertThat(fakePaymentGateway.createCount()).isEqualTo(1);
        }

        @DisplayName("422 + 정확한 code + false 인데 orderId 가 다르면, 거절로 종결하지 않고 시도 UNKNOWN + 충돌(ORDER_MISMATCH)·예약 유지·재고 3 이다 (결정 ⑱).")
        @Test
        void keepsUnknownWithConflict_whenRejectionOrderIdDiffers() {
            // arrange
            fakePaymentGateway.willRejectWithMismatchedOrderId();
            Long userId = saveUser();
            Long productId = saveProduct("상품", 1000L, 5);
            Long orderId = createOrder(userId, productId, 2);

            // act
            OrderConfirmInfo info = confirm(userId, orderId, "k1");

            // assert
            assertThat(info.settled()).isFalse();
            assertThat(info.attemptStatus()).isEqualTo("UNKNOWN");
            assertUnknownWithConflict(orderId, productId, PaymentAttempt.ConflictReason.ORDER_MISMATCH);
            assertThat(fakePaymentGateway.transactions()).isEmpty();
            assertThat(fakePaymentGateway.createCount()).isEqualTo(1);
        }

        private void assertUnknownWithConflict(Long orderId, Long productId, PaymentAttempt.ConflictReason expectedReason) {
            PaymentAttempt attempt = attemptOf(orderId);
            assertThat(attempt.getStatus()).isEqualTo(PaymentAttempt.Status.UNKNOWN);
            assertThat(attempt.getTransactionKey()).isNull();
            assertThat(attempt.isConflicted()).isTrue();
            assertThat(attempt.getConflictReason()).isEqualTo(expectedReason);
            assertThat(attempt.getConflictedAt()).isNotNull();
            assertThat(orderOf(orderId).getStatus()).isEqualTo(Order.OrderStatus.PAYMENT_PENDING);
            assertThat(reservationsOf(orderId)).singleElement()
                .extracting(StockReservation::getStatus).isEqualTo(StockReservation.Status.RESERVED);
            assertThat(stockOf(productId)).isEqualTo(3);
        }
    }

    @DisplayName("기존 기능 보호 — ")
    @Nested
    class ExistingRulesPreserved {
        @DisplayName("이전에 POINT 로 확정된 주문을 확정하면, 기존 '이미 확정된 주문' 오류이고 create 는 0회다.")
        @Test
        void rejectsAlreadyConfirmed_whenOrderWasPaidWithPoint() {
            // arrange - 교체 전 POINT 확정 주문을 DB 상태로 재현한다
            Long userId = saveUser();
            Long productId = saveProduct("상품", 1000L, 5);
            Long orderId = createOrder(userId, productId, 2);
            jdbcTemplate.update(
                "UPDATE orders SET status = 'CONFIRMED', payment_method = 'POINT', paid_amount = ? WHERE id = ?",
                2000L, orderId);

            // act
            CoreException result = assertThrows(CoreException.class, () -> confirm(userId, orderId, "k1"));

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.CONFLICT);
            assertThat(result.getMessage()).isEqualTo("이미 확정된 주문입니다.");
            assertThat(paymentAttemptJpaRepository.count()).isZero();
            assertThat(reservationsOf(orderId)).isEmpty();
            assertThat(stockOf(productId)).isEqualTo(5);
            assertThat(orderOf(orderId).getPaymentMethod()).isEqualTo(Order.PaymentMethod.POINT);
            assertThat(fakePaymentGateway.createCount()).isZero();
        }

        @DisplayName("남의 주문을 확정하면, 기존 소유권 오류(404)이고 create 는 0회다.")
        @Test
        void rejectsNotFound_whenRequesterIsNotOwner() {
            // arrange
            Long ownerId = saveUser();
            Long otherId = saveUser();
            Long productId = saveProduct("상품", 1000L, 5);
            Long orderId = createOrder(ownerId, productId, 2);

            // act
            CoreException result = assertThrows(CoreException.class, () -> confirm(otherId, orderId, "k1"));

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
            assertThat(result.getMessage()).isEqualTo("[id = " + orderId + "] 주문을 찾을 수 없습니다.");
            assertThat(orderOf(orderId).getStatus()).isEqualTo(Order.OrderStatus.DRAFT);
            assertThat(paymentAttemptJpaRepository.count()).isZero();
            assertThat(stockOf(productId)).isEqualTo(5);
            assertThat(fakePaymentGateway.createCount()).isZero();
        }

        @DisplayName("확정을 거치고 나면, 카드 번호는 어떤 DB 컬럼에도 없고 로그 출력에도 남지 않는다.")
        @Test
        void neverStoresOrLogsCardNumber() {
            // arrange
            Long userId = saveUser();
            Long productId = saveProduct("상품", 1000L, 5);
            Long orderId = createOrder(userId, productId, 2);

            // act
            confirm(userId, orderId, "k1");

            // assert - 스키마에 카드 번호 컬럼이 없다
            Integer cardColumns = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()"
                    + " AND (column_name LIKE '%card_no%' OR column_name LIKE '%cardno%' OR column_name LIKE '%card_number%')",
                Integer.class);
            assertThat(cardColumns).isZero();

            // assert - 저장된 어떤 값에도 카드 번호가 없다 (시도 1건이 실제로 저장된 상태에서 확인)
            assertThat(paymentAttemptJpaRepository.count()).isEqualTo(1);
            for (String table : List.of("payment_attempts", "orders", "stock_reservations")) {
                List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM " + table);
                for (Map<String, Object> row : rows) {
                    for (Object value : row.values()) {
                        assertThat(String.valueOf(value)).as(table).doesNotContain(CARD_NO);
                    }
                }
            }

            // assert - 로그 메시지·인자 어디에도 카드 번호가 없다
            for (ILoggingEvent event : logs.list) {
                assertThat(event.getFormattedMessage()).doesNotContain(CARD_NO);
                assertThat(Arrays.toString(event.getArgumentArray())).doesNotContain(CARD_NO);
            }
        }
    }

    private OrderConfirmInfo confirm(Long userId, Long orderId, String attemptKey) {
        return confirm(userId, orderId, attemptKey, CARD_TYPE, CARD_NO);
    }

    private OrderConfirmInfo confirm(Long userId, Long orderId, String attemptKey, String cardType, String cardNo) {
        return orderFacade.confirmOrder(userId, orderId, new OrderCommand.Confirm(attemptKey, cardType, cardNo));
    }

    private static Logger appLogger() {
        return (Logger) LoggerFactory.getLogger("com.loopers");
    }

    private Long saveUser() {
        return userJpaRepository.save(new User()).getId();
    }

    private Long saveProduct(String name, long price, int stock) {
        Product product = new Product(1L, name, new Price(price));
        product.changeStock(stock);
        return productJpaRepository.save(product).getId();
    }

    private void savePoint(Long userId, long balance) {
        Point point = new Point(userId);
        point.charge(balance);
        pointJpaRepository.save(point);
    }

    private Long createOrder(Long userId, Long productId, int quantity) {
        return orderFacade.createOrder(userId, List.of(new OrderCommand.Item(productId, quantity))).orderId();
    }

    private Order orderOf(Long orderId) {
        return orderJpaRepository.findById(orderId).orElseThrow();
    }

    private PaymentAttempt attemptOf(Long orderId) {
        return paymentAttemptJpaRepository.findByOrderId(orderId).orElseThrow();
    }

    private List<StockReservation> reservationsOf(Long orderId) {
        return stockReservationJpaRepository.findAllByOrderId(orderId);
    }

    private int stockOf(Long productId) {
        return productJpaRepository.findById(productId).orElseThrow().getStock().getQuantity();
    }

    private long balanceOf(Long userId) {
        return pointJpaRepository.findByUserId(userId).orElseThrow().getBalance();
    }
}