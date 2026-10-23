package com.loopers.concurrency;

import com.loopers.application.order.OrderAdminInfo;
import com.loopers.application.order.OrderFacade;
import com.loopers.application.order.OrderItemInfo;
import com.loopers.application.payment.PaymentFacade;
import com.loopers.application.payment.PaymentOutcome;
import com.loopers.application.point.PointFacade;
import com.loopers.application.product.ProductFacade;
import com.loopers.concurrency.ConcurrentRunner.Outcome;
import com.loopers.concurrency.ConcurrentRunner.WorkerResult;
import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderModel;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.point.PointModel;
import com.loopers.domain.product.ProductModel;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.order.OrderJpaRepository;
import com.loopers.infrastructure.order.StockReservationJpaRepository;
import com.loopers.infrastructure.payment.PaymentAttemptJpaRepository;
import com.loopers.infrastructure.point.PointJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.support.pg.PaymentTestSupport;
import com.loopers.support.pg.TestPgResetExtension;
import com.loopers.support.pg.TestPgServer;
import com.loopers.support.pg.TestPgStatus;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * 주문 확정(PG 결제 시작)의 동시 실행을 확인한다 (design.md 7-3절, 9-4·9-5절).
 * 재고·중복 요청 경쟁은 TX A(재고 확보와 결제 시도 저장)가 보호하고, PG 호출은 그 잠금 밖에서 일어난다.
 * 결제는 포인트를 쓰지 않으므로 잔액은 어떤 경쟁에서도 충전 외의 이유로 바뀌지 않는다.
 */
@SpringBootTest
@ExtendWith(TestPgResetExtension.class)
class OrderConcurrencyTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final long USER_ID = 1L;

    @Autowired
    private OrderFacade orderFacade;

    @Autowired
    private PaymentFacade paymentFacade;

    @Autowired
    private PointFacade pointFacade;

    @Autowired
    private ProductFacade productFacade;

    @Autowired
    private BrandJpaRepository brandJpaRepository;

    @Autowired
    private ProductJpaRepository productJpaRepository;

    @Autowired
    private PointJpaRepository pointJpaRepository;

    @Autowired
    private OrderJpaRepository orderJpaRepository;

    @Autowired
    private PaymentAttemptJpaRepository paymentAttemptJpaRepository;

    @Autowired
    private StockReservationJpaRepository stockReservationJpaRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private final TestPgServer pg = TestPgServer.shared();

    private BrandModel brand;

    @BeforeEach
    void setUp() {
        brand = brandJpaRepository.save(new BrandModel("나이키"));
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("재고 5에 서로 다른 주문 8건을 동시에 확정하면, 5건만 재고를 확보해 결제 대기가 되고 3건은 재고 부족으로 거절되며 재고는 0이 된다.")
    @Test
    void reservesOnlyUpToStock_whenOrdersCompeteForStock() {
        // arrange: 구매자별로 잔액이 충분하고, 주문은 모두 다르다
        ProductModel product = saveProduct(5, 10_000L);
        List<Long> userIds = new ArrayList<>();
        List<Long> orderIds = new ArrayList<>();
        List<Callable<PaymentOutcome>> tasks = new ArrayList<>();
        for (long userId = 1; userId <= 8; userId++) {
            saveBalance(userId, 100_000L);
            Long orderId = saveDraft(userId, new OrderItem(product.getId(), 1, 10_000L));
            userIds.add(userId);
            orderIds.add(orderId);
            tasks.add(confirm(orderId, userId));
        }

        // act
        List<WorkerResult<PaymentOutcome>> results = ConcurrentRunner.runTogether(tasks, TIMEOUT);

        // assert
        List<OrderAdminInfo> orders = orderIds.stream().map(orderFacade::getOrderForAdmin).toList();
        int reservedQuantity = orders.stream()
            .filter(order -> order.status() == OrderStatus.PAYMENT_PENDING)
            .flatMap(order -> order.items().stream())
            .mapToInt(OrderItemInfo::quantity)
            .sum();
        List<Long> balances = userIds.stream().map(this::balanceOf).toList();
        assertAll(
            () -> assertThat(count(results, Outcome.SUCCESS)).as("결제 시작").isEqualTo(5),
            () -> assertThat(count(results, Outcome.BUSINESS_REJECTED)).as("재고 부족").isEqualTo(3),
            () -> assertThat(technicalErrors(results)).as("기술 오류").isEmpty(),
            () -> assertThat(rejectedTypes(results)).containsOnly(ErrorType.BAD_REQUEST),
            () -> assertThat(stockOf(product)).as("최종 재고").isZero(),
            // 요청 결과와 DB가 같은 이야기를 한다
            () -> assertThat(orders).filteredOn(order -> order.status() == OrderStatus.PAYMENT_PENDING).hasSize(5),
            () -> assertThat(orders).filteredOn(order -> order.status() == OrderStatus.DRAFT).hasSize(3),
            // 수량: 초기 재고 - 확보한 주문의 수량 합 = 최종 재고
            () -> assertThat(5 - reservedQuantity).isEqualTo(stockOf(product)),
            // 확보한 주문만 결제 시도·예약·PG 거래를 한 개씩 갖는다
            () -> assertThat(paymentAttemptJpaRepository.count()).isEqualTo(5),
            () -> assertThat(stockReservationJpaRepository.count()).isEqualTo(5),
            () -> assertThat(pg.createCount()).as("PG 생성 호출").isEqualTo(5),
            () -> assertThat(pg.transactionCount()).isEqualTo(5),
            // 포인트는 어떤 구매자도 쓰지 않는다
            () -> assertThat(balances).containsOnly(100_000L),
            () -> assertThat(total(results)).isEqualTo(8)
        );
    }

    @DisplayName("재고를 확보한 주문 5건의 결제가 모두 성공으로 확인돼도, 재고는 다시 줄지 않고 0 그대로다.")
    @Test
    void doesNotDecreaseStockAgain_whenAllReservedOrdersSucceed() {
        // arrange
        ProductModel product = saveProduct(5, 10_000L);
        List<Long> orderIds = new ArrayList<>();
        for (long userId = 1; userId <= 5; userId++) {
            Long orderId = saveDraft(userId, new OrderItem(product.getId(), 1, 10_000L));
            PaymentTestSupport.startPayment(paymentFacade, orderId, userId);
            orderIds.add(orderId);
        }
        assertThat(stockOf(product)).isZero();

        // act: 성공 확인(reconcile)을 동시에 한다
        List<Callable<PaymentOutcome>> tasks = new ArrayList<>();
        for (int i = 0; i < orderIds.size(); i++) {
            Long orderId = orderIds.get(i);
            long userId = i + 1;
            PaymentTestSupport.completeAt(pg, orderId, TestPgStatus.SUCCESS);
            tasks.add(() -> paymentFacade.reconcile(orderId, userId));
        }
        List<WorkerResult<PaymentOutcome>> results = ConcurrentRunner.runTogether(tasks, TIMEOUT);

        // assert
        assertAll(
            () -> assertThat(count(results, Outcome.SUCCESS)).as("확인").isEqualTo(5),
            () -> assertThat(technicalErrors(results)).as("기술 오류").isEmpty(),
            () -> assertThat(orderIds.stream().map(orderFacade::getOrderForAdmin))
                .allMatch(order -> order.status() == OrderStatus.CONFIRMED),
            () -> assertThat(stockOf(product)).as("성공 뒤에도 재고는 그대로").isZero(),
            () -> assertThat(pg.createCount()).isEqualTo(5)
        );
    }

    @DisplayName("한 사용자의 주문 3건을 동시에 확정해도, PG 결제는 포인트를 쓰지 않아 3건 모두 결제 대기가 되고 잔액은 그대로다.")
    @Test
    void leavesBalanceUntouched_whenOneUserConfirmsSeveralOrders() {
        // arrange: 잔액(10,000원)이 주문 합계(3 x 4,000원)보다 적어도 PG 결제는 잔액을 보지 않는다
        ProductModel product = saveProduct(100, 4_000L);
        saveBalance(USER_ID, 10_000L);
        List<Long> orderIds = new ArrayList<>();
        List<Callable<PaymentOutcome>> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Long orderId = saveDraft(USER_ID, new OrderItem(product.getId(), 1, 4_000L));
            orderIds.add(orderId);
            tasks.add(confirm(orderId, USER_ID));
        }

        // act
        List<WorkerResult<PaymentOutcome>> results = ConcurrentRunner.runTogether(tasks, TIMEOUT);

        // assert
        assertAll(
            () -> assertThat(count(results, Outcome.SUCCESS)).as("결제 시작").isEqualTo(3),
            () -> assertThat(technicalErrors(results)).as("기술 오류").isEmpty(),
            () -> assertThat(balanceOf(USER_ID)).as("최종 잔액").isEqualTo(10_000L),
            () -> assertThat(orderIds.stream().map(orderFacade::getOrderForAdmin))
                .allMatch(order -> order.status() == OrderStatus.PAYMENT_PENDING),
            () -> assertThat(stockOf(product)).as("최종 재고").isEqualTo(97),
            () -> assertThat(total(results)).isEqualTo(3)
        );
    }

    @DisplayName("잔액 10,000원에서 충전 2,000원과 7,000원 주문 확정을 동시에 하면, 둘 다 성공하고 잔액은 충전분만 늘어 12,000원이 된다.")
    @Test
    void bothSucceed_andOnlyChargeChangesBalance_whenChargeAndConfirmRunTogether() {
        // arrange
        ProductModel product = saveProduct(10, 7_000L);
        saveBalance(USER_ID, 10_000L);
        Long orderId = saveDraft(USER_ID, new OrderItem(product.getId(), 1, 7_000L));
        List<Callable<Object>> tasks = List.of(
            () -> pointFacade.charge(USER_ID, 2_000L),
            () -> PaymentTestSupport.startPayment(paymentFacade, orderId, USER_ID)
        );

        // act
        List<WorkerResult<Object>> results = ConcurrentRunner.runTogether(tasks, TIMEOUT);

        // assert
        assertAll(
            () -> assertThat(count(results, Outcome.SUCCESS)).as("성공").isEqualTo(2),
            () -> assertThat(technicalErrors(results)).as("기술 오류").isEmpty(),
            // 잔액: 초기 잔액 + 성공 충전액 (결제는 포인트를 쓰지 않는다)
            () -> assertThat(balanceOf(USER_ID)).as("최종 잔액").isEqualTo(10_000L + 2_000L),
            () -> assertThat(orderFacade.getOrderForAdmin(orderId).status()).isEqualTo(OrderStatus.PAYMENT_PENDING),
            () -> assertThat(stockOf(product)).isEqualTo(9),
            () -> assertThat(total(results)).isEqualTo(2)
        );
    }

    @DisplayName("같은 주문을 같은 요청(같은 시도 키)으로 동시에 여러 번 확정하면, 시도·예약·재고 확보·PG 생성 호출이 각각 한 번뿐이다.")
    @Test
    void appliesOnce_whenSameRequestIsConfirmedConcurrently() {
        // arrange
        ProductModel product = saveProduct(5, 10_000L);
        saveBalance(USER_ID, 100_000L);
        Long orderId = saveDraft(USER_ID, new OrderItem(product.getId(), 1, 10_000L));
        List<Callable<PaymentOutcome>> tasks = List.of(
            confirm(orderId, USER_ID), confirm(orderId, USER_ID), confirm(orderId, USER_ID), confirm(orderId, USER_ID)
        );

        // act
        List<WorkerResult<PaymentOutcome>> results = ConcurrentRunner.runTogether(tasks, TIMEOUT);

        // assert: 늦게 온 요청은 저장된 상태를 돌려받고 발송하지 않는다
        assertAll(
            () -> assertThat(count(results, Outcome.SUCCESS)).as("응답").isEqualTo(4),
            () -> assertThat(technicalErrors(results)).as("기술 오류").isEmpty(),
            () -> assertThat(stockOf(product)).as("재고는 한 번만 확보된다").isEqualTo(4),
            () -> assertThat(paymentAttemptJpaRepository.count()).as("결제 시도").isEqualTo(1),
            () -> assertThat(stockReservationJpaRepository.count()).as("재고 예약").isEqualTo(1),
            () -> assertThat(pg.createCount()).as("PG 생성 호출").isEqualTo(1),
            () -> assertThat(pg.transactionCount()).isEqualTo(1),
            () -> assertThat(balanceOf(USER_ID)).isEqualTo(100_000L)
        );
    }

    @DisplayName("같은 주문을 서로 다른 시도 키로 동시에 확정하면, 한 요청만 시도를 만들고 나머지는 409로 거절되며 PG 생성 호출은 한 번뿐이다.")
    @Test
    void rejectsOthers_whenSameOrderIsConfirmedWithDifferentKeys() {
        // arrange
        ProductModel product = saveProduct(5, 10_000L);
        saveBalance(USER_ID, 100_000L);
        Long orderId = saveDraft(USER_ID, new OrderItem(product.getId(), 1, 10_000L));
        List<Callable<PaymentOutcome>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            String key = "different-key-" + i;
            tasks.add(() -> paymentFacade.confirm(orderId, USER_ID, key, PaymentTestSupport.CARD_TYPE, PaymentTestSupport.CARD_NO));
        }

        // act
        List<WorkerResult<PaymentOutcome>> results = ConcurrentRunner.runTogether(tasks, TIMEOUT);

        // assert
        assertAll(
            () -> assertThat(count(results, Outcome.SUCCESS)).as("시도를 만든 요청").isEqualTo(1),
            () -> assertThat(count(results, Outcome.BUSINESS_REJECTED)).as("거절").isEqualTo(3),
            () -> assertThat(technicalErrors(results)).as("기술 오류").isEmpty(),
            () -> assertThat(rejectedTypes(results)).containsOnly(ErrorType.CONFLICT),
            () -> assertThat(stockOf(product)).as("재고는 한 번만 확보된다").isEqualTo(4),
            () -> assertThat(paymentAttemptJpaRepository.count()).isEqualTo(1),
            () -> assertThat(pg.createCount()).as("PG 생성 호출").isEqualTo(1)
        );
    }

    @DisplayName("관리자의 재고 설정과 주문 확정이 동시에 일어나도, 결과는 둘 중 한 순서로 처리한 값이다.")
    @Test
    void endsWithOneOfTheSerialResults_whenAdminSetsStockWhileOrderIsConfirmed() {
        // arrange
        ProductModel product = saveProduct(5, 10_000L);
        saveBalance(USER_ID, 100_000L);
        Long orderId = saveDraft(USER_ID, new OrderItem(product.getId(), 1, 10_000L));
        List<Callable<Object>> tasks = List.of(
            () -> productFacade.changeStock(product.getId(), 10),
            () -> PaymentTestSupport.startPayment(paymentFacade, orderId, USER_ID)
        );

        // act
        List<WorkerResult<Object>> results = ConcurrentRunner.runTogether(tasks, TIMEOUT);

        // assert: 설정 후 확보면 9, 확보 후 설정이면 10 (설정은 예약을 뺀 가용 수량을 최종값으로 정한다)
        assertAll(
            () -> assertThat(count(results, Outcome.SUCCESS)).as("성공").isEqualTo(2),
            () -> assertThat(technicalErrors(results)).as("기술 오류").isEmpty(),
            () -> assertThat(stockOf(product)).isIn(9, 10)
        );
    }

    @DisplayName("같은 상품 두 개를 서로 반대 순서로 담은 주문들을 동시에 확정해도, 교착 없이 모두 재고를 확보한다.")
    @Test
    void reservesAll_whenOrdersHoldSameProductsInOppositeOrder() {
        // arrange
        ProductModel first = saveProduct(10, 1_000L);
        ProductModel second = saveProduct(10, 1_000L);
        List<Callable<PaymentOutcome>> tasks = new ArrayList<>();
        for (long userId = 1; userId <= 8; userId++) {
            saveBalance(userId, 100_000L);
            OrderItem a = new OrderItem(first.getId(), 1, 1_000L);
            OrderItem b = new OrderItem(second.getId(), 1, 1_000L);
            Long orderId = userId <= 4 ? saveDraft(userId, a, b) : saveDraft(userId, b, a);
            tasks.add(confirm(orderId, userId));
        }

        // act
        List<WorkerResult<PaymentOutcome>> results = ConcurrentRunner.runTogether(tasks, TIMEOUT);

        // assert
        assertAll(
            () -> assertThat(count(results, Outcome.SUCCESS)).as("결제 시작").isEqualTo(8),
            () -> assertThat(technicalErrors(results)).as("기술 오류(교착 포함)").isEmpty(),
            () -> assertThat(stockOf(first)).isEqualTo(2),
            () -> assertThat(stockOf(second)).isEqualTo(2)
        );
    }

    private Callable<PaymentOutcome> confirm(Long orderId, long userId) {
        return () -> PaymentTestSupport.startPayment(paymentFacade, orderId, userId);
    }

    private ProductModel saveProduct(int stock, long price) {
        return productJpaRepository.save(new ProductModel(brand.getId(), "상품", price, stock));
    }

    private void saveBalance(long userId, long balance) {
        PointModel point = new PointModel(userId);
        point.charge(balance);
        pointJpaRepository.save(point);
    }

    private Long saveDraft(long userId, OrderItem... items) {
        return orderJpaRepository.save(new OrderModel(userId, List.of(items))).getId();
    }

    private int stockOf(ProductModel product) {
        return productJpaRepository.findById(product.getId()).orElseThrow().getStock();
    }

    private long balanceOf(long userId) {
        return pointJpaRepository.findByUserId(userId).orElseThrow().getBalance();
    }

    private static long count(List<? extends WorkerResult<?>> results, Outcome outcome) {
        return ConcurrentRunner.count(results, outcome);
    }

    private static long total(List<? extends WorkerResult<?>> results) {
        return count(results, Outcome.SUCCESS)
            + count(results, Outcome.BUSINESS_REJECTED)
            + count(results, Outcome.TECHNICAL_ERROR);
    }

    private static List<String> technicalErrors(List<? extends WorkerResult<?>> results) {
        return results.stream()
            .filter(result -> result.outcome() == Outcome.TECHNICAL_ERROR)
            .map(result -> String.valueOf(result.error()))
            .toList();
    }

    private static List<ErrorType> rejectedTypes(List<? extends WorkerResult<?>> results) {
        return results.stream()
            .filter(result -> result.outcome() == Outcome.BUSINESS_REJECTED)
            .map(result -> ((CoreException) result.error()).getErrorType())
            .toList();
    }
}
