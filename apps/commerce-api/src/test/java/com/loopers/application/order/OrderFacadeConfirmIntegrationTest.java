package com.loopers.application.order;

import com.loopers.application.point.PointFacade;
import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.common.Money;
import com.loopers.domain.order.OrderLines;
import com.loopers.domain.order.OrderRepository;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.order.PaymentMethod;
import com.loopers.domain.point.PointGroup;
import com.loopers.domain.point.PointHistory;
import com.loopers.domain.point.PointHistoryType;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.user.UserModel;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.order.OrderJpaRepository;
import com.loopers.infrastructure.point.PointGroupJpaRepository;
import com.loopers.infrastructure.point.PointHistoryJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;

/**
 * 주문 확정 유스케이스 (ORD-02~05). 실제 빈과 테스트 DB로, 확정 뒤 DB에 남은 재고·그룹의 남은 금액·사용 이력·주문 상태를 확인한다.
 */
@SpringBootTest
class OrderFacadeConfirmIntegrationTest {

    @Autowired
    private OrderFacade orderFacade;

    @Autowired
    private PointFacade pointFacade;

    @MockitoSpyBean
    private OrderRepository orderRepository;

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
        return orderFacade.create(owner.getId(), List.of(
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
            pointFacade.charge(user.getId(), 10_000);
            Long orderId = createOrderOf7000(user);

            // act
            OrderInfo confirmed = orderFacade.confirm(user.getId(), orderId);

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
            pointFacade.charge(user.getId(), 4_000);
            pointFacade.charge(user.getId(), 5_000);
            List<PointGroup> groups = pointGroupJpaRepository.findAll(Sort.by("id"));
            Long orderId = createOrderOf7000(user);

            // act
            orderFacade.confirm(user.getId(), orderId);

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
            pointFacade.charge(user.getId(), 5_000);
            Long orderId = createOrderOf7000(user);

            // act & assert
            assertThatThrownBy(() -> orderFacade.confirm(user.getId(), orderId))
                .isInstanceOf(CoreException.class)
                .extracting("errorType").isEqualTo(ErrorType.CONFLICT);
            assertNothingChanged(orderId, Money.of(5_000));
        }

        @DisplayName("ORD-03·P-19 주문 뒤 가격이 1,000원에서 1,200원으로 바뀌면 409이고, 주문은 DRAFT로 남으며 나머지도 그대로다.")
        @Test
        void rejectsWhenPriceChanged() {
            // arrange
            pointFacade.charge(user.getId(), 10_000);
            Long orderId = createOrderOf7000(user);
            ProductModel changed = productJpaRepository.findById(airMax.getId()).orElseThrow();
            changed.update("에어맥스", 1_200);
            productJpaRepository.save(changed);

            // act & assert
            assertThatThrownBy(() -> orderFacade.confirm(user.getId(), orderId))
                .isInstanceOf(CoreException.class)
                .extracting("errorType").isEqualTo(ErrorType.CONFLICT);
            assertNothingChanged(orderId, Money.of(10_000));
        }

        @DisplayName("ORD-02 남의 주문을 확정하면 404이고, 아무것도 바뀌지 않는다.")
        @Test
        void hidesOthersOrder() {
            // arrange
            UserModel other = userJpaRepository.save(new UserModel("다른 고객"));
            pointFacade.charge(user.getId(), 10_000);
            Long othersOrder = createOrderOf7000(other);

            // act & assert
            assertThatThrownBy(() -> orderFacade.confirm(user.getId(), othersOrder))
                .isInstanceOf(CoreException.class)
                .extracting("errorType").isEqualTo(ErrorType.NOT_FOUND);
            assertNothingChanged(othersOrder, Money.of(10_000));
        }

        @DisplayName("ORD-02·P-17 이미 확정한 주문을 다시 확정하면 409이고, 재고와 포인트는 한 번만 빠져 있다.")
        @Test
        void rejectsConfirmingTwice() {
            // arrange
            pointFacade.charge(user.getId(), 20_000);
            Long orderId = createOrderOf7000(user);
            orderFacade.confirm(user.getId(), orderId);

            // act & assert
            assertThatThrownBy(() -> orderFacade.confirm(user.getId(), orderId))
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

        @DisplayName("ORD-05 재고·포인트 차감과 사용 이력 저장이 끝난 뒤 주문 저장에서 실패하면, 재고·그룹의 남은 금액·사용 이력·주문 상태가 모두 요청 전으로 돌아간다.")
        @Test
        void rollsBackEverythingWhenLastStepFails() {
            // arrange
            pointFacade.charge(user.getId(), 10_000);
            Long orderId = createOrderOf7000(user);
            // 확정된 주문을 저장할 때만 실패시킨다. 저장 위치가 변경보다 앞으로 옮겨지면 이 조건이 맞지 않아 테스트가 실패한다.
            doThrow(new IllegalStateException("주문 저장 실패")).when(orderRepository).save(argThat(order -> !order.isDraft()));

            // act & assert
            assertThatThrownBy(() -> orderFacade.confirm(user.getId(), orderId))
                .isInstanceOf(IllegalStateException.class);
            assertNothingChanged(orderId, Money.of(10_000));
        }
    }
}
