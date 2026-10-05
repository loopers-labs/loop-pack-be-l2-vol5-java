package com.loopers.order.application;

import com.loopers.brand.adapter.out.persistence.BrandJpaRepository;
import com.loopers.brand.application.port.in.BrandCommandUseCase;
import com.loopers.brand.domain.BrandModel;
import com.loopers.order.adapter.out.persistence.OrderJpaRepository;
import com.loopers.order.application.port.in.OrderCommandUseCase;
import com.loopers.order.domain.OrderLines;
import com.loopers.order.domain.OrderModel;
import com.loopers.order.domain.OrderStatus;
import com.loopers.point.application.PointQueryService;
import com.loopers.point.application.port.in.PointExpirationUseCase;
import com.loopers.point.application.port.in.PointCommandUseCase;
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

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.IntStream;

import static com.loopers.support.concurrency.ConcurrentRunner.count;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * W3 C-6 (plan.md T-6~T-9): 실제 application·repository·MySQL을 지나는 동시 요청에서, 요청별 결과와 새로 읽은 DB가 기대와 맞는다.
 * 각 작업은 독립 트랜잭션으로 실제 UseCase를 부르고, 테스트 메서드는 트랜잭션으로 감싸지 않는다.
 * 확인하는 식: 성공 + 업무 거절 + 기술 오류 = 전체 요청 / 초기 재고 − 성공 수량 = 최종 재고 / 초기 잔액 + 성공 충전 − 성공 결제 = 최종 잔액.
 */
@SpringBootTest
class OrderConfirmConcurrencyTest {

    private static final String OUT_OF_STOCK = "재고가 부족합니다";
    private static final String NOT_ENOUGH_POINTS = "포인트 잔액이 부족합니다";
    private static final String ALREADY_CONFIRMED = "이미 확정된 주문입니다";

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

    @DisplayName("T-6 재고 5, 서로 다른 구매자의 1개짜리 DRAFT 8건을 동시에 확정 → 확정 5 · 재고 부족 3 · 기술 오류 0 · 최종 재고 0")
    @Test
    void stockRace() throws Exception {
        // arrange: 구매자마다 충분한 잔액과 서로 다른 DRAFT 주문
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
            .<Callable<?>>mapToObj(i -> () -> orderCommandUseCase.confirm(buyers.get(i).getId(), orderIds.get(i)))
            .toList();
        List<Outcome> outcomes = ConcurrentRunner.run(confirms);

        // assert: 요청 결과
        assertThat(count(outcomes, Kind.SUCCESS)).isEqualTo(5);
        assertThat(outcomes.stream().filter(outcome -> outcome.rejectedWith(OUT_OF_STOCK)).count()).isEqualTo(3);
        assertThat(count(outcomes, Kind.TECHNICAL_ERROR)).isZero();
        assertThat(count(outcomes, Kind.SUCCESS) + count(outcomes, Kind.BUSINESS_REJECTED) + count(outcomes, Kind.TECHNICAL_ERROR))
            .isEqualTo(confirms.size());

        // assert: 새로 읽은 DB — 초기 재고 5 − 성공 수량 5 = 최종 재고 0, 확정된 주문만 결제됐다
        assertThat(stockOf(limited)).isZero();
        for (int i = 0; i < 8; i++) {
            boolean confirmed = outcomes.get(i).kind() == Kind.SUCCESS;
            OrderModel order = orderJpaRepository.findById(orderIds.get(i)).orElseThrow();
            assertThat(order.getStatus()).isEqualTo(confirmed ? OrderStatus.CONFIRMED : OrderStatus.DRAFT);
            assertThat(balanceOf(buyers.get(i))).isEqualTo(confirmed ? 9_000 : 10_000);
        }
    }

    @DisplayName("T-7 한 사용자 잔액 10,000, 서로 다른 4,000원 DRAFT 3건을 동시에 확정 → 확정 2 · 잔액 부족 1 · 기술 오류 0 · 최종 잔액 2,000, 거절된 주문의 재고는 그대로")
    @Test
    void pointRace() throws Exception {
        // arrange: 재고는 충분히 두어 잔액 보호의 실패를 재고 부족이 가리지 않게 한다
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
            .<Callable<?>>map(orderId -> () -> orderCommandUseCase.confirm(buyer.getId(), orderId))
            .toList();
        List<Outcome> outcomes = ConcurrentRunner.run(confirms);

        // assert: 요청 결과
        assertThat(count(outcomes, Kind.SUCCESS)).isEqualTo(2);
        assertThat(outcomes.stream().filter(outcome -> outcome.rejectedWith(NOT_ENOUGH_POINTS)).count()).isEqualTo(1);
        assertThat(count(outcomes, Kind.TECHNICAL_ERROR)).isZero();
        assertThat(count(outcomes, Kind.SUCCESS) + count(outcomes, Kind.BUSINESS_REJECTED) + count(outcomes, Kind.TECHNICAL_ERROR))
            .isEqualTo(confirms.size());

        // assert: 새로 읽은 DB — 10,000 − 4,000 × 2 = 2,000, 확정된 주문의 상품만 재고가 줄었다
        assertThat(balanceOf(buyer)).isEqualTo(2_000);
        assertThat(useHistoryCount()).isEqualTo(2);
        assertThat(historySumMatchesRemaining()).isTrue();
        for (int i = 0; i < 3; i++) {
            boolean confirmed = outcomes.get(i).kind() == Kind.SUCCESS;
            assertThat(stockOf(products.get(i))).isEqualTo(confirmed ? 9 : 10);
            assertThat(orderJpaRepository.findById(orderIds.get(i)).orElseThrow().getStatus())
                .isEqualTo(confirmed ? OrderStatus.CONFIRMED : OrderStatus.DRAFT);
        }
    }

    @DisplayName("T-8 잔액 10,000에서 2,000 충전과 7,000원 확정을 동시에 → 둘 다 성공 · 기술 오류 0 · 최종 잔액 5,000")
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
            () -> orderCommandUseCase.confirm(buyer.getId(), orderId)
        ));

        // assert: 10,000 + 2,000 − 7,000 = 5,000
        assertThat(outcomes).extracting(Outcome::kind).containsExactly(Kind.SUCCESS, Kind.SUCCESS);
        assertThat(balanceOf(buyer)).isEqualTo(5_000);
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
            () -> orderCommandUseCase.confirm(buyer.getId(), orderId),
            () -> productCommandUseCase.update(product.getId(), "새 이름", 1_000)
        ));

        // assert: 어느 순서든 재고 차감과 이름 변경이 모두 남는다
        assertThat(outcomes).extracting(Outcome::kind).containsExactly(Kind.SUCCESS, Kind.SUCCESS);
        ProductModel reloaded = productJpaRepository.findById(product.getId()).orElseThrow();
        assertThat(reloaded.getStock()).isEqualTo(4);
        assertThat(reloaded.getName()).isEqualTo("새 이름");
        assertThat(balanceOf(buyer)).isEqualTo(9_000);
    }

    @DisplayName("R-3 같은 주문을 동시에 3번 확정 → 확정 1 · 이미 확정 409 2 · 기술 오류 0, 재고·잔액은 한 번만 줄어든다")
    @Test
    void sameOrderConfirmedConcurrently() throws Exception {
        // arrange
        UserModel buyer = userJpaRepository.save(new UserModel("구매자"));
        pointCommandUseCase.charge(buyer.getId(), 10_000);
        ProductModel product = saveProduct("상품", 1_000, 5);
        Long orderId = createOrder(buyer, product, 2);

        // act
        List<Callable<?>> confirms = List.of(
            () -> orderCommandUseCase.confirm(buyer.getId(), orderId),
            () -> orderCommandUseCase.confirm(buyer.getId(), orderId),
            () -> orderCommandUseCase.confirm(buyer.getId(), orderId)
        );
        List<Outcome> outcomes = ConcurrentRunner.run(confirms);

        // assert
        assertThat(count(outcomes, Kind.SUCCESS)).isEqualTo(1);
        assertThat(outcomes.stream().filter(outcome -> outcome.rejectedWith(ALREADY_CONFIRMED)).count()).isEqualTo(2);
        assertThat(count(outcomes, Kind.TECHNICAL_ERROR)).isZero();
        assertThat(stockOf(product)).isEqualTo(3);
        assertThat(balanceOf(buyer)).isEqualTo(8_000);
        assertThat(useHistoryCount()).isEqualTo(1);
    }

    @DisplayName("R-6 같은 그룹에 대한 확정(7,000원)과 만료 배치를 동시에 → 확정이 먼저면 사용 7,000·만료 3,000, 배치가 먼저면 잔액 부족 409·만료 10,000. 어느 쪽이든 그룹의 이력 합 = 남은 금액 0")
    @Test
    void confirmAndExpiration() throws Exception {
        // arrange: 배치의 기준 시각을 유효기간 뒤로 두어, 확정이 쓰려는 그룹을 배치도 만료 대상으로 본다
        UserModel buyer = userJpaRepository.save(new UserModel("구매자"));
        pointCommandUseCase.charge(buyer.getId(), 10_000);
        ProductModel product = saveProduct("상품", 7_000, 5);
        Long orderId = createOrder(buyer, product, 1);
        ZonedDateTime batchTime = ZonedDateTime.now().plusYears(10);

        // act
        List<Outcome> outcomes = ConcurrentRunner.run(List.of(
            () -> orderCommandUseCase.confirm(buyer.getId(), orderId),
            () -> pointExpirationUseCase.expireAll(batchTime)
        ));

        // assert: 배치는 항상 성공하고, 확정은 성공 또는 잔액 부족(업무 거절)만 가능하다
        Outcome confirm = outcomes.get(0);
        assertThat(outcomes.get(1).kind()).isEqualTo(Kind.SUCCESS);
        assertThat(confirm.kind() == Kind.SUCCESS || confirm.rejectedWith(NOT_ENOUGH_POINTS)).isTrue();
        boolean confirmed = confirm.kind() == Kind.SUCCESS;
        assertThat(jdbcTemplate.queryForObject("SELECT SUM(remaining) FROM point_groups", Long.class)).isZero();
        assertThat(historySumMatchesRemaining()).isTrue();
        assertThat(jdbcTemplate.queryForObject("SELECT SUM(amount) FROM point_histories WHERE type = 'EXPIRE'", Long.class))
            .isEqualTo(confirmed ? -3_000L : -10_000L);
        assertThat(stockOf(product)).isEqualTo(confirmed ? 4 : 5);
        assertThat(orderJpaRepository.findById(orderId).orElseThrow().getStatus())
            .isEqualTo(confirmed ? OrderStatus.CONFIRMED : OrderStatus.DRAFT);
    }

    @DisplayName("T-9 같은 상품에 대한 확정과 브랜드 일괄 삭제를 동시에 → 상품은 반드시 삭제되고, 확정이 먼저였으면 재고 4·CONFIRMED, 삭제가 먼저였으면 409·재고 5·DRAFT다")
    @Test
    void confirmAndBrandDelete() throws Exception {
        // arrange
        UserModel buyer = userJpaRepository.save(new UserModel("구매자"));
        pointCommandUseCase.charge(buyer.getId(), 10_000);
        ProductModel product = saveProduct("상품", 1_000, 5);
        Long orderId = createOrder(buyer, product, 1);

        // act
        List<Outcome> outcomes = ConcurrentRunner.run(List.of(
            () -> orderCommandUseCase.confirm(buyer.getId(), orderId),
            () -> {
                brandCommandUseCase.delete(nike.getId());
                return null;
            }
        ));

        // assert: 삭제는 항상 성공하고, 확정이 삭제를 되살리지 않는다
        Outcome confirm = outcomes.get(0);
        assertThat(outcomes.get(1).kind()).isEqualTo(Kind.SUCCESS);
        assertThat(confirm.kind()).isNotEqualTo(Kind.TECHNICAL_ERROR);
        ProductModel reloaded = productJpaRepository.findById(product.getId()).orElseThrow();
        assertThat(reloaded.getDeletedAt()).isNotNull();
        boolean confirmed = confirm.kind() == Kind.SUCCESS;
        assertThat(reloaded.getStock()).isEqualTo(confirmed ? 4 : 5);
        assertThat(balanceOf(buyer)).isEqualTo(confirmed ? 9_000 : 10_000);
        assertThat(orderJpaRepository.findById(orderId).orElseThrow().getStatus())
            .isEqualTo(confirmed ? OrderStatus.CONFIRMED : OrderStatus.DRAFT);
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
