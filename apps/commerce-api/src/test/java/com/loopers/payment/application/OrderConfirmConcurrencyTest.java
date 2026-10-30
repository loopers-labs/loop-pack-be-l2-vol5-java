package com.loopers.payment.application;

import com.loopers.brand.adapter.out.persistence.BrandJpaRepository;
import com.loopers.brand.application.port.in.BrandCommandUseCase;
import com.loopers.brand.domain.BrandModel;
import com.loopers.order.adapter.out.persistence.OrderJpaRepository;
import com.loopers.order.application.port.in.OrderCommandUseCase;
import com.loopers.order.domain.OrderLines;
import com.loopers.order.domain.OrderModel;
import com.loopers.order.domain.OrderStatus;
import com.loopers.payment.adapter.out.persistence.PaymentJpaRepository;
import com.loopers.payment.application.port.in.PaymentDispatchUseCase;
import com.loopers.payment.application.port.in.PaymentInfo;
import com.loopers.payment.application.port.in.PaymentRequestCommand;
import com.loopers.payment.application.port.in.PaymentRequestUseCase;
import com.loopers.point.application.PointQueryService;
import com.loopers.point.application.port.in.PointCommandUseCase;
import com.loopers.point.application.port.in.PointExpirationUseCase;
import com.loopers.product.adapter.out.persistence.ProductJpaRepository;
import com.loopers.product.application.port.in.ProductCommandUseCase;
import com.loopers.product.domain.ProductModel;
import com.loopers.support.concurrency.ConcurrentRunner;
import com.loopers.support.concurrency.ConcurrentRunner.Kind;
import com.loopers.support.concurrency.ConcurrentRunner.Outcome;
import com.loopers.user.adapter.out.persistence.UserJpaRepository;
import com.loopers.user.domain.UserModel;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.IntStream;

import static com.loopers.support.concurrency.ConcurrentRunner.count;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * W3 C-6 (plan.md T-6~T-9): 실제 application·repository·MySQL을 지나는 동시 요청에서, 요청별 결과와 새로 읽은 DB가 기대와 맞는다.
 * W5부터 주문 확정은 PG 결제 요청 TX A(주문 → 상품 FOR UPDATE → 재고 선점 → 결제 시도·장부)라 이 파일을 order에서 payment로 옮겼다 (W5 plan.md 10-1).
 * 동시성의 대상은 TX A이므로 commit 뒤 발송 입구만 mock으로 바꾼다 — PG 호출·결과 반영의 동시성은 결제 테스트(R-3 등)가 맡는다.
 * 확인하는 식: 성공 + 업무 거절 + 기술 오류 = 전체 요청 / 초기 재고 − 성공(선점) 수량 = 최종 재고 / 확정 요청은 포인트를 바꾸지 않는다(C-1).
 */
@SpringBootTest
class OrderConfirmConcurrencyTest {

    private static final String OUT_OF_STOCK = "재고가 부족합니다";
    private static final String ALREADY_ATTEMPTED = "이미 다른 결제 시도가 있는 주문입니다";
    private static final String NOT_SELLABLE = "더 이상 판매하지 않는 상품입니다";

    @Autowired
    private PaymentRequestUseCase paymentRequestUseCase;

    @MockitoBean
    private PaymentDispatchUseCase paymentDispatchUseCase;

    @Autowired
    private OrderCommandUseCase orderCommandUseCase;

    @Autowired
    private PointCommandUseCase pointCommandUseCase;

    @Autowired
    private PointQueryService pointQueryService;

    @Autowired
    private PointExpirationUseCase pointExpirationUseCase;

    @Autowired
    private ProductCommandUseCase productCommandUseCase;

    @Autowired
    private BrandCommandUseCase brandCommandUseCase;

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Autowired
    private BrandJpaRepository brandJpaRepository;

    @Autowired
    private ProductJpaRepository productJpaRepository;

    @Autowired
    private OrderJpaRepository orderJpaRepository;

    @Autowired
    private PaymentJpaRepository paymentJpaRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private BrandModel nike;

    @BeforeEach
    void setUp() {
        nike = brandJpaRepository.save(new BrandModel("나이키", null));
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    // W5: "확정 5"는 재고를 선점해 PAYMENT_PENDING이 된 주문 5건이다. 잔액은 누구도 줄지 않는다 (C-1).
    @DisplayName("T-6 재고 5, 서로 다른 구매자의 1개짜리 DRAFT 8건을 동시에 확정 → 확정 5 · 재고 부족 3 · 기술 오류 0 · 최종 재고 0")
    @Test
    void stockRace() throws Exception {
        // arrange: 구매자마다 잔액과 서로 다른 DRAFT 주문
        ProductModel limited = saveProduct("한정판", 1_000, 5);
        List<UserModel> buyers = new ArrayList<>();
        List<Long> orderIds = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            UserModel buyer = userJpaRepository.save(new UserModel("구매자" + i));
            pointCommandUseCase.charge(buyer.getId(), 10_000);
            buyers.add(buyer);
            orderIds.add(createOrder(buyer, limited, 1));
        }

        // act
        List<Callable<?>> confirms = IntStream.range(0, 8)
            .<Callable<?>>mapToObj(i -> () -> confirm(buyers.get(i), orderIds.get(i)))
            .toList();
        List<Outcome> outcomes = ConcurrentRunner.run(confirms);

        // assert: 요청 결과
        assertThat(count(outcomes, Kind.SUCCESS)).isEqualTo(5);
        assertThat(outcomes.stream().filter(outcome -> outcome.rejectedWith(OUT_OF_STOCK)).count()).isEqualTo(3);
        assertThat(count(outcomes, Kind.TECHNICAL_ERROR)).isZero();
        assertThat(count(outcomes, Kind.SUCCESS) + count(outcomes, Kind.BUSINESS_REJECTED) + count(outcomes, Kind.TECHNICAL_ERROR))
            .isEqualTo(confirms.size());

        // assert: 새로 읽은 DB — 초기 재고 5 − 선점 수량 5 = 최종 재고 0, 선점한 주문만 결제 대기이고 잔액은 그대로다
        assertThat(stockOf(limited)).isZero();
        assertThat(reservedQuantity()).isEqualTo(5);
        assertThat(paymentJpaRepository.count()).isEqualTo(5);
        for (int i = 0; i < 8; i++) {
            boolean accepted = outcomes.get(i).kind() == Kind.SUCCESS;
            OrderModel order = orderJpaRepository.findById(orderIds.get(i)).orElseThrow();
            assertThat(order.getStatus()).isEqualTo(accepted ? OrderStatus.PAYMENT_PENDING : OrderStatus.DRAFT);
            assertThat(balanceOf(buyers.get(i))).isEqualTo(10_000);
        }
        verify(paymentDispatchUseCase, times(5)).dispatch(any());
    }

    // W5 이전: 한 사용자 잔액 10,000으로 4,000원 3건을 동시에 확정 → 확정 2·잔액 부족 1 (잔액 보호). 확정은 이제 포인트를 쓰지 않으므로
    // 잔액 경쟁 자체가 없다 — 같은 사용자의 동시 확정 3건이 모두 접수되고, 잔액·이력은 그대로이며 각 상품은 1개씩만 선점되는 것으로 옮긴다 (C-1).
    @DisplayName("T-7 한 사용자 잔액 10,000, 서로 다른 4,000원 DRAFT 3건을 동시에 확정 → 접수 3 · 기술 오류 0 · 잔액 10,000 그대로(C-1) · 사용 이력 0, 각 상품 재고 9")
    @Test
    void pointRace() throws Exception {
        // arrange
        UserModel buyer = userJpaRepository.save(new UserModel("구매자"));
        pointCommandUseCase.charge(buyer.getId(), 10_000);
        List<ProductModel> products = new ArrayList<>();
        List<Long> orderIds = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            ProductModel product = saveProduct("상품" + i, 4_000, 10);
            products.add(product);
            orderIds.add(createOrder(buyer, product, 1));
        }

        // act
        List<Callable<?>> confirms = orderIds.stream()
            .<Callable<?>>map(orderId -> () -> confirm(buyer, orderId))
            .toList();
        List<Outcome> outcomes = ConcurrentRunner.run(confirms);

        // assert: 요청 결과
        assertThat(count(outcomes, Kind.SUCCESS)).isEqualTo(3);
        assertThat(count(outcomes, Kind.TECHNICAL_ERROR)).isZero();
        assertThat(count(outcomes, Kind.SUCCESS) + count(outcomes, Kind.BUSINESS_REJECTED) + count(outcomes, Kind.TECHNICAL_ERROR))
            .isEqualTo(confirms.size());

        // assert: 새로 읽은 DB — 잔액·이력은 그대로, 각 주문의 상품만 1개씩 선점됐다
        assertThat(balanceOf(buyer)).isEqualTo(10_000);
        assertThat(useHistoryCount()).isZero();
        assertThat(historySumMatchesRemaining()).isTrue();
        for (int i = 0; i < 3; i++) {
            assertThat(stockOf(products.get(i))).isEqualTo(9);
            assertThat(orderJpaRepository.findById(orderIds.get(i)).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        }
    }

    // W5: 확정이 포인트를 쓰지 않으므로 최종 잔액은 10,000 + 2,000 = 12,000이다 (이전 기대 5,000 = 10,000 + 2,000 − 7,000).
    @DisplayName("T-8 잔액 10,000에서 2,000 충전과 7,000원 확정을 동시에 → 둘 다 성공 · 기술 오류 0 · 최종 잔액 12,000(확정은 포인트를 쓰지 않음, C-1)")
    @Test
    void chargeAndPay() throws Exception {
        // arrange
        UserModel buyer = userJpaRepository.save(new UserModel("구매자"));
        pointCommandUseCase.charge(buyer.getId(), 10_000);
        ProductModel product = saveProduct("상품", 7_000, 10);
        Long orderId = createOrder(buyer, product, 1);

        // act
        List<Outcome> outcomes = ConcurrentRunner.run(List.of(
            () -> pointCommandUseCase.charge(buyer.getId(), 2_000),
            () -> confirm(buyer, orderId)
        ));

        // assert: 10,000 + 2,000 = 12,000
        assertThat(outcomes).extracting(Outcome::kind).containsExactly(Kind.SUCCESS, Kind.SUCCESS);
        assertThat(balanceOf(buyer)).isEqualTo(12_000);
        assertThat(stockOf(product)).isEqualTo(9);
    }

    @DisplayName("T-9·R-4 같은 상품에 대한 확정과 관리자 이름 변경(가격 그대로)을 동시에 → 순서와 관계없이 둘 다 성공 · 최종 재고 4 · 새 이름. 관리자 쪽 전체 컬럼 UPDATE가 재고를 5로 되돌리지 않는다")
    @Test
    void confirmAndAdminRename() throws Exception {
        // arrange
        UserModel buyer = userJpaRepository.save(new UserModel("구매자"));
        pointCommandUseCase.charge(buyer.getId(), 10_000);
        ProductModel product = saveProduct("상품", 1_000, 5);
        Long orderId = createOrder(buyer, product, 1);

        // act
        List<Outcome> outcomes = ConcurrentRunner.run(List.of(
            () -> confirm(buyer, orderId),
            () -> productCommandUseCase.update(product.getId(), "새 이름", 1_000)
        ));

        // assert: 어느 순서든 재고 선점과 이름 변경이 모두 남는다
        assertThat(outcomes).extracting(Outcome::kind).containsExactly(Kind.SUCCESS, Kind.SUCCESS);
        ProductModel reloaded = productJpaRepository.findById(product.getId()).orElseThrow();
        assertThat(reloaded.getStock()).isEqualTo(4);
        assertThat(reloaded.getName()).isEqualTo("새 이름");
        assertThat(balanceOf(buyer)).isEqualTo(10_000);
    }

    // W5: 같은 주문의 동시 확정은 "같은 시도 키 재전송"과 "다른 시도 키"로 나뉜다 (W5 plan.md 2·4-1).
    // 같은 키면 모두 같은 시도를 돌려받고(새 효과 0), 다른 키면 하나만 접수되고 나머지는 409다. 어느 쪽이든 결제 시도·선점은 1번이다.
    @DisplayName("R-3 같은 주문을 같은 시도 키로 동시에 3번 확정 → 모두 같은 시도를 돌려받고 · 기술 오류 0, 결제 시도·재고 선점·발송은 한 번뿐이며 잔액은 그대로다")
    @Test
    void sameOrderConfirmedConcurrently() throws Exception {
        // arrange
        UserModel buyer = userJpaRepository.save(new UserModel("구매자"));
        pointCommandUseCase.charge(buyer.getId(), 10_000);
        ProductModel product = saveProduct("상품", 1_000, 5);
        Long orderId = createOrder(buyer, product, 2);

        // act
        List<Callable<?>> confirms = List.of(
            () -> confirm(buyer, orderId, "same-key"),
            () -> confirm(buyer, orderId, "same-key"),
            () -> confirm(buyer, orderId, "same-key")
        );
        List<Outcome> outcomes = ConcurrentRunner.run(confirms);

        // assert
        assertThat(count(outcomes, Kind.SUCCESS)).isEqualTo(3);
        assertThat(count(outcomes, Kind.TECHNICAL_ERROR)).isZero();
        assertThat(paymentJpaRepository.count()).isEqualTo(1);
        assertThat(reservedQuantity()).isEqualTo(2);
        assertThat(stockOf(product)).isEqualTo(3);
        assertThat(balanceOf(buyer)).isEqualTo(10_000);
        assertThat(useHistoryCount()).isZero();
        verify(paymentDispatchUseCase, times(1)).dispatch(any());
    }

    @DisplayName("R-3 같은 주문을 서로 다른 시도 키로 동시에 3번 확정 → 접수 1 · 이미 시도한 주문 409 2 · 기술 오류 0, 재고 선점은 한 번만 일어난다")
    @Test
    void sameOrderConfirmedConcurrentlyWithDifferentKeys() throws Exception {
        // arrange
        UserModel buyer = userJpaRepository.save(new UserModel("구매자"));
        ProductModel product = saveProduct("상품", 1_000, 5);
        Long orderId = createOrder(buyer, product, 2);

        // act
        List<Callable<?>> confirms = List.of(
            () -> confirm(buyer, orderId, "key-1"),
            () -> confirm(buyer, orderId, "key-2"),
            () -> confirm(buyer, orderId, "key-3")
        );
        List<Outcome> outcomes = ConcurrentRunner.run(confirms);

        // assert
        assertThat(count(outcomes, Kind.SUCCESS)).isEqualTo(1);
        assertThat(outcomes.stream().filter(outcome -> outcome.rejectedWith(ALREADY_ATTEMPTED)).count()).isEqualTo(2);
        assertThat(count(outcomes, Kind.TECHNICAL_ERROR)).isZero();
        assertThat(paymentJpaRepository.count()).isEqualTo(1);
        assertThat(reservedQuantity()).isEqualTo(2);
        assertThat(stockOf(product)).isEqualTo(3);
    }

    // W5 이전: 확정과 만료 배치가 같은 포인트 그룹을 두고 경쟁했다(확정이 먼저면 사용 7,000·만료 3,000, 배치가 먼저면 잔액 부족 409).
    // 확정은 이제 포인트 그룹을 잠그지도 쓰지도 않으므로 경쟁이 사라진다 — 순서와 관계없이 둘 다 성공하고, 배치는 10,000 전부를 만료하며
    // 그룹의 이력 합 = 남은 금액 0이 유지되고, 확정은 재고만 선점한다 (C-1).
    @DisplayName("R-6 같은 사용자의 확정(7,000원)과 만료 배치를 동시에 → 둘 다 성공 · 만료 10,000 · 사용 0, 그룹의 이력 합 = 남은 금액 0, 재고 4·PAYMENT_PENDING")
    @Test
    void confirmAndExpiration() throws Exception {
        // arrange: 배치의 기준 시각을 유효기간 뒤로 두어, 사용자의 그룹을 배치가 만료 대상으로 본다
        UserModel buyer = userJpaRepository.save(new UserModel("구매자"));
        pointCommandUseCase.charge(buyer.getId(), 10_000);
        ProductModel product = saveProduct("상품", 7_000, 5);
        Long orderId = createOrder(buyer, product, 1);
        ZonedDateTime batchTime = ZonedDateTime.now().plusYears(10);

        // act
        List<Outcome> outcomes = ConcurrentRunner.run(List.of(
            () -> confirm(buyer, orderId),
            () -> pointExpirationUseCase.expireAll(batchTime)
        ));

        // assert
        assertThat(outcomes).extracting(Outcome::kind).containsExactly(Kind.SUCCESS, Kind.SUCCESS);
        assertThat(jdbcTemplate.queryForObject("SELECT SUM(remaining) FROM point_groups", Long.class)).isZero();
        assertThat(historySumMatchesRemaining()).isTrue();
        assertThat(jdbcTemplate.queryForObject("SELECT SUM(amount) FROM point_histories WHERE type = 'EXPIRE'", Long.class))
            .isEqualTo(-10_000L);
        assertThat(useHistoryCount()).isZero();
        assertThat(stockOf(product)).isEqualTo(4);
        assertThat(orderJpaRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
    }

    // W5: "확정이 먼저"는 재고를 선점해 PAYMENT_PENDING이 된 경우다.
    @DisplayName("T-9 같은 상품에 대한 확정과 브랜드 일괄 삭제를 동시에 → 상품은 반드시 삭제되고, 확정이 먼저였으면 재고 4·PAYMENT_PENDING(선점), 삭제가 먼저였으면 409·재고 5·DRAFT다")
    @Test
    void confirmAndBrandDelete() throws Exception {
        // arrange
        UserModel buyer = userJpaRepository.save(new UserModel("구매자"));
        pointCommandUseCase.charge(buyer.getId(), 10_000);
        ProductModel product = saveProduct("상품", 1_000, 5);
        Long orderId = createOrder(buyer, product, 1);

        // act
        List<Outcome> outcomes = ConcurrentRunner.run(List.of(
            () -> confirm(buyer, orderId),
            () -> {
                brandCommandUseCase.delete(nike.getId());
                return null;
            }
        ));

        // assert: 삭제는 항상 성공하고, 확정이 삭제를 되살리지 않는다
        Outcome confirm = outcomes.get(0);
        assertThat(outcomes.get(1).kind()).isEqualTo(Kind.SUCCESS);
        assertThat(confirm.kind() == Kind.SUCCESS || confirm.rejectedWith(NOT_SELLABLE)).isTrue();
        ProductModel reloaded = productJpaRepository.findById(product.getId()).orElseThrow();
        assertThat(reloaded.getDeletedAt()).isNotNull();
        boolean accepted = confirm.kind() == Kind.SUCCESS;
        assertThat(reloaded.getStock()).isEqualTo(accepted ? 4 : 5);
        assertThat(reservedQuantity()).isEqualTo(accepted ? 1 : 0);
        assertThat(balanceOf(buyer)).isEqualTo(10_000);
        assertThat(orderJpaRepository.findById(orderId).orElseThrow().getStatus())
            .isEqualTo(accepted ? OrderStatus.PAYMENT_PENDING : OrderStatus.DRAFT);
    }

    /** W5 확정 = PG 결제 요청 TX A. 주문마다 다른 시도 키. */
    private PaymentInfo confirm(UserModel buyer, Long orderId) {
        return confirm(buyer, orderId, "attempt-" + orderId);
    }

    private PaymentInfo confirm(UserModel buyer, Long orderId, String attemptKey) {
        return paymentRequestUseCase.request(new PaymentRequestCommand(buyer.getId(), orderId, attemptKey, "SAMSUNG", "1234-5678-9814-1451"));
    }

    private ProductModel saveProduct(String name, long price, int stock) {
        return productJpaRepository.save(new ProductModel(nike.getId(), name, price, stock));
    }

    private Long createOrder(UserModel buyer, ProductModel product, int quantity) {
        return orderCommandUseCase.create(buyer.getId(), List.of(new OrderLines.Line(product.getId(), quantity))).id();
    }

    private int stockOf(ProductModel product) {
        return productJpaRepository.findById(product.getId()).orElseThrow().getStock();
    }

    private long reservedQuantity() {
        return jdbcTemplate.queryForObject("SELECT COALESCE(SUM(quantity), 0) FROM stock_reservations WHERE status = 'RESERVED'", Long.class);
    }

    private long useHistoryCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM point_histories WHERE type = 'USE'", Long.class);
    }

    /**
     * W2 4-1: 그룹마다 이력 합(충전 + 사용·만료의 음수) = 남은 금액.
     */
    private boolean historySumMatchesRemaining() {
        Long mismatched = jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM point_groups g
            WHERE g.remaining <> (SELECT COALESCE(SUM(h.amount), 0) FROM point_histories h WHERE h.group_id = g.id)
            """, Long.class);
        return mismatched == 0;
    }

    private long balanceOf(UserModel user) {
        return pointQueryService.getBalance(user.getId()).balance();
    }
}
