package com.loopers.order.application;

import com.loopers.brand.adapter.out.persistence.BrandJpaRepository;
import com.loopers.brand.domain.BrandModel;
import com.loopers.common.domain.Money;
import com.loopers.order.adapter.out.persistence.OrderJpaRepository;
import com.loopers.order.application.port.in.OrderCommandUseCase;
import com.loopers.order.application.port.in.OrderInfo;
import com.loopers.order.application.port.out.OrderPort;
import com.loopers.order.domain.OrderLines;
import com.loopers.order.domain.OrderStatus;
import com.loopers.order.domain.PaymentMethod;
import com.loopers.point.adapter.out.persistence.PointGroupJpaRepository;
import com.loopers.point.adapter.out.persistence.PointHistoryJpaRepository;
import com.loopers.point.application.port.in.PointCommandUseCase;
import com.loopers.point.domain.PointGroup;
import com.loopers.point.domain.PointHistory;
import com.loopers.point.domain.PointHistoryType;
import com.loopers.product.adapter.out.persistence.ProductJpaRepository;
import com.loopers.product.domain.ProductModel;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.user.adapter.out.persistence.UserJpaRepository;
import com.loopers.user.domain.UserModel;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;

/**
 * 주문 확정 유스케이스 (ORD-02~05). 실제 빈과 테스트 DB로, 확정 뒤 DB에 남은 재고·그룹의 남은 금액·사용 이력·주문 상태를 확인한다.
 */
@SpringBootTest
class OrderCommandServiceConfirmIntegrationTest {

    @Autowired
    private OrderCommandUseCase orderCommandUseCase;

    @Autowired
    private PointCommandUseCase pointCommandUseCase;

    @MockitoSpyBean
    private OrderPort orderPort;

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Autowired
    private BrandJpaRepository brandJpaRepository;

    @Autowired
    private ProductJpaRepository productJpaRepository;

    @Autowired
    private OrderJpaRepository orderJpaRepository;

    @Autowired
    private PointGroupJpaRepository pointGroupJpaRepository;

    @Autowired
    private PointHistoryJpaRepository pointHistoryJpaRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private UserModel user;
    private ProductModel airMax;
    private ProductModel airForce;

    @BeforeEach
    void setUp() {
        user = userJpaRepository.save(new UserModel("고객"));
        BrandModel nike = brandJpaRepository.save(new BrandModel("나이키", null));
        airMax = productJpaRepository.save(new ProductModel(nike.getId(), "에어맥스", 1_000, 10));
        airForce = productJpaRepository.save(new ProductModel(nike.getId(), "에어포스", 2_000, 10));
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    /** 에어맥스 1,000원 × 3 + 에어포스 2,000원 × 2 = 7,000원짜리 DRAFT 주문. */
    private Long createOrderOf7000(UserModel owner) {
        return orderCommandUseCase.create(owner.getId(), List.of(
            new OrderLines.Line(airMax.getId(), 3),
            new OrderLines.Line(airForce.getId(), 2)
        )).id();
    }

    private int stockOf(ProductModel product) {
        return productJpaRepository.findById(product.getId()).orElseThrow().getStock();
    }

    private List<Money> remainingOfGroups() {
        return pointGroupJpaRepository.findAll().stream().map(PointGroup::getRemaining).toList();
    }

    private List<PointHistory> useHistories() {
        return pointHistoryJpaRepository.findAll().stream()
            .filter(history -> history.getType() == PointHistoryType.USE)
            .toList();
    }

    private OrderStatus statusOf(Long orderId) {
        return orderJpaRepository.findById(orderId).orElseThrow().getStatus();
    }

    /** 확정이 거절됐을 때 네 가지가 모두 요청 전과 같은지 (ORD-05). */
    private void assertNothingChanged(Long orderId, Money remaining) {
        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.DRAFT);
        assertThat(stockOf(airMax)).isEqualTo(10);
        assertThat(stockOf(airForce)).isEqualTo(10);
        assertThat(remainingOfGroups()).containsExactly(remaining);
        assertThat(useHistories()).isEmpty();
    }

    @DisplayName("확정할 수 있으면, ")
    @Nested
    class Success {

        @DisplayName("ORD-05 잔액 10,000원으로 7,000원 주문을 확정하면 CONFIRMED·결제액 7,000원이 되고, 재고·그룹의 남은 금액·사용 이력이 함께 반영된다.")
        @Test
        void confirmsAndAppliesAllChanges() {
            // arrange
            pointCommandUseCase.charge(user.getId(), 10_000);
            Long orderId = createOrderOf7000(user);

            // act
            OrderInfo confirmed = orderCommandUseCase.confirm(user.getId(), orderId);

            // assert
            assertThat(confirmed.status()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(confirmed.paidAmount()).isEqualTo(7_000L);
            assertThat(confirmed.paymentMethod()).isEqualTo(PaymentMethod.POINT);
            assertThat(confirmed.confirmedAt()).isNotNull();

            assertThat(statusOf(orderId)).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(stockOf(airMax)).isEqualTo(7);
            assertThat(stockOf(airForce)).isEqualTo(8);
            assertThat(remainingOfGroups()).containsExactly(Money.of(3_000));
            assertThat(useHistories())
                .extracting(PointHistory::getAmount, PointHistory::getOrderId)
                .containsExactly(tuple(-7_000L, orderId));
        }

        @DisplayName("ORD-05 4,000원 그룹과 5,000원 그룹에서 7,000원을 내면, 쓴 그룹마다 사용 이력을 한 줄씩 남긴다.")
        @Test
        void recordsUsagePerGroup() {
            // arrange
            pointCommandUseCase.charge(user.getId(), 4_000);
            pointCommandUseCase.charge(user.getId(), 5_000);
            List<PointGroup> groups = pointGroupJpaRepository.findAll(Sort.by("id"));
            Long orderId = createOrderOf7000(user);

            // act
            orderCommandUseCase.confirm(user.getId(), orderId);

            // assert
            assertThat(useHistories())
                .extracting(PointHistory::getGroupId, PointHistory::getAmount)
                .containsExactlyInAnyOrder(
                    tuple(groups.get(0).getId(), -4_000L),
                    tuple(groups.get(1).getId(), -3_000L)
                );
            assertThat(pointGroupJpaRepository.findAll())
                .extracting(PointGroup::getAmount, PointGroup::getRemaining)
                .containsExactlyInAnyOrder(
                    tuple(Money.of(4_000), Money.of(0)),
                    tuple(Money.of(5_000), Money.of(2_000))
                );
        }
    }

    @DisplayName("확정할 수 없으면, ")
    @Nested
    class Rejection {

        @DisplayName("ORD-04 잔액 5,000원으로 7,000원 주문을 확정하면 409이고, 주문·재고·그룹의 남은 금액·사용 이력이 모두 그대로다.")
        @Test
        void rejectsWhenBalanceIsShort() {
            // arrange
            pointCommandUseCase.charge(user.getId(), 5_000);
            Long orderId = createOrderOf7000(user);

            // act & assert
            assertThatThrownBy(() -> orderCommandUseCase.confirm(user.getId(), orderId))
                .isInstanceOf(CoreException.class)
                .extracting("errorType").isEqualTo(ErrorType.CONFLICT);
            assertNothingChanged(orderId, Money.of(5_000));
        }

        @DisplayName("ORD-03·P-19 주문 뒤 가격이 1,000원에서 1,200원으로 바뀌면 409이고, 주문은 DRAFT로 남으며 나머지도 그대로다.")
        @Test
        void rejectsWhenPriceChanged() {
            // arrange
            pointCommandUseCase.charge(user.getId(), 10_000);
            Long orderId = createOrderOf7000(user);
            ProductModel changed = productJpaRepository.findById(airMax.getId()).orElseThrow();
            changed.update("에어맥스", 1_200);
            productJpaRepository.save(changed);

            // act & assert
            assertThatThrownBy(() -> orderCommandUseCase.confirm(user.getId(), orderId))
                .isInstanceOf(CoreException.class)
                .extracting("errorType").isEqualTo(ErrorType.CONFLICT);
            assertNothingChanged(orderId, Money.of(10_000));
        }

        @DisplayName("ORD-02 남의 주문을 확정하면 404이고, 아무것도 바뀌지 않는다.")
        @Test
        void hidesOthersOrder() {
            // arrange
            UserModel other = userJpaRepository.save(new UserModel("다른 고객"));
            pointCommandUseCase.charge(user.getId(), 10_000);
            Long othersOrder = createOrderOf7000(other);

            // act & assert
            assertThatThrownBy(() -> orderCommandUseCase.confirm(user.getId(), othersOrder))
                .isInstanceOf(CoreException.class)
                .extracting("errorType").isEqualTo(ErrorType.NOT_FOUND);
            assertNothingChanged(othersOrder, Money.of(10_000));
        }

        @DisplayName("ORD-02·P-17 이미 확정한 주문을 다시 확정하면 409이고, 재고와 포인트는 한 번만 빠져 있다.")
        @Test
        void rejectsConfirmingTwice() {
            // arrange
            pointCommandUseCase.charge(user.getId(), 20_000);
            Long orderId = createOrderOf7000(user);
            orderCommandUseCase.confirm(user.getId(), orderId);

            // act & assert
            assertThatThrownBy(() -> orderCommandUseCase.confirm(user.getId(), orderId))
                .isInstanceOf(CoreException.class)
                .extracting("errorType").isEqualTo(ErrorType.CONFLICT);
            assertThat(stockOf(airMax)).isEqualTo(7);
            assertThat(stockOf(airForce)).isEqualTo(8);
            assertThat(remainingOfGroups()).containsExactly(Money.of(13_000));
            assertThat(useHistories()).hasSize(1);
        }
    }

    @DisplayName("변경 도중 실패하면, ")
    @Nested
    class Rollback {

        @DisplayName("ORD-05·W3 C-4 재고·그룹의 남은 금액·사용 이력의 SQL이 실제로 나간 뒤 주문 저장에서 실패하면, 새로 읽은 재고·남은 금액·사용 이력·주문 상태가 모두 요청 전이다.")
        @Test
        void rollsBackEverythingWhenLastStepFails() {
            // arrange
            pointCommandUseCase.charge(user.getId(), 10_000);
            Long orderId = createOrderOf7000(user);
            List<Object> sentBeforeFailure = new ArrayList<>();
            // 확정된 주문을 저장할 때만 실패시킨다. 저장 위치가 변경보다 앞으로 옮겨지면 이 조건이 맞지 않아 테스트가 실패한다.
            doAnswer(invocation -> {
                // 같은 트랜잭션의 커넥션으로 읽으면 앞 단계의 UPDATE·INSERT가 이미 DB에 나가 있다 (아직 commit 전)
                sentBeforeFailure.add(jdbcTemplate.queryForObject("SELECT stock FROM products WHERE id = ?", Integer.class, airMax.getId()));
                sentBeforeFailure.add(jdbcTemplate.queryForObject("SELECT SUM(remaining) FROM point_groups", Long.class));
                sentBeforeFailure.add(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM point_histories WHERE type = 'USE'", Long.class));
                throw new IllegalStateException("주문 저장 실패");
            }).when(orderPort).save(argThat(order -> !order.isDraft()));


            // act
            assertThatThrownBy(() -> orderCommandUseCase.confirm(user.getId(), orderId))
                    .isInstanceOf(IllegalStateException.class);

            // assert: 실패 직전에는 재고 10 → 7, 남은 금액 10,000 → 3,000, 사용 이력 1줄이 DB에 반영돼 있었다
            assertThat(sentBeforeFailure).containsExactly(7, 3_000L, 1L);
            // assert: 트랜잭션이 끝난 뒤 새로 읽으면 전부 요청 전이다
            assertNothingChanged(orderId, Money.of(10_000));
        }
    }
}
