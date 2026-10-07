package com.loopers.application.order;

import com.loopers.application.point.PointFacade;
import com.loopers.domain.order.OrderItemCommand;
import com.loopers.domain.order.OrderModel;
import com.loopers.domain.order.OrderRepository;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.user.UserModel;
import com.loopers.fixture.OrderStateReader;
import com.loopers.fixture.OrderStateReader.ConfirmState;
import com.loopers.fixture.ProductFixture;
import com.loopers.fixture.UserFixture;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 주문 확정의 실제 변경 SQL 이 전송된 뒤 마지막 Order 저장 경계에서 실패하면 전체가 rollback 되는지 확인한다.
 * 테스트 전체를 부모 트랜잭션으로 감싸지 않고, 준비 데이터를 먼저 commit 한 뒤 실제 OrderConfirmFacade 프록시를 호출한다.
 * 운영 코드에는 flush·실패 분기를 넣지 않고, 이 클래스의 OrderRepository spy 에서만 flush 후 예외를 던진다.
 */
@DisplayName("주문 확정은 실제 변경 SQL 이 flush 된 뒤 실패해도 Order·Point·Product·History 변경을 모두 rollback 한다.")
@SpringBootTest
@AutoConfigureMockMvc
class OrderTransactionIntegrationTest {

    private static final String INJECTED_FAILURE = "flush 이후 마지막 Order 저장 경계에서 주입한 실패";

    @MockitoSpyBean
    private OrderRepository orderRepository;

    @Autowired
    private OrderConfirmFacade orderConfirmFacade;
    @Autowired
    private OrderFacade orderFacade;
    @Autowired
    private PointFacade pointFacade;
    @Autowired
    private UserFixture userFixture;
    @Autowired
    private ProductFixture productFixture;
    @Autowired
    private OrderStateReader orderStateReader;
    @PersistenceContext
    private EntityManager entityManager;
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    /** 실패 주입 시점에 현재 트랜잭션에서 읽은 DB 값: [주문 상태, 포인트 사용액, 결제액, 잔액, 재고들, 주문 원인 History 수]. */
    private List<Object> readInCurrentTransaction(Long orderId, Long userId, List<Long> productIds) {
        Object[] order = (Object[]) entityManager.createNativeQuery(
                "SELECT status, used_point_amount, payment_amount FROM orders WHERE id = :orderId")
            .setParameter("orderId", orderId)
            .getSingleResult();
        Number balance = (Number) entityManager.createNativeQuery(
                "SELECT balance FROM point WHERE user_id = :userId")
            .setParameter("userId", userId)
            .getSingleResult();
        List<Long> stocks = productIds.stream()
            .map(productId -> ((Number) entityManager.createNativeQuery(
                    "SELECT stock_quantity FROM product WHERE id = :productId")
                .setParameter("productId", productId)
                .getSingleResult()).longValue())
            .toList();
        Number stockHistories = (Number) entityManager.createNativeQuery(
                "SELECT COUNT(*) FROM stock_history WHERE order_id = :orderId AND cause = 'ORDER_DEDUCTION'")
            .setParameter("orderId", orderId)
            .getSingleResult();
        Number pointHistories = (Number) entityManager.createNativeQuery(
                "SELECT COUNT(*) FROM point_history WHERE order_id = :orderId AND cause = 'ORDER_USE'")
            .setParameter("orderId", orderId)
            .getSingleResult();
        return Arrays.asList(order[0], toLong(order[1]), toLong(order[2]), balance.longValue(), stocks,
            stockHistories.longValue(), pointHistories.longValue());
    }

    private static Long toLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    @DisplayName("마지막 save 진입에서 flush 로 차감·확정·History SQL 이 전송된 뒤 예외가 나면, 예외를 전파하고 새 조회에서 모든 상태가 확정 전과 같다.")
    @Test
    void rollsBackEverythingWhenLastOrderSaveFailsAfterFlush() {
        UserModel user = userFixture.createUserWithPoint();
        pointFacade.charge(user.getId(), 10_000L);
        ProductModel shirt = productFixture.createProduct("티셔츠", 2_000L, 5L);
        ProductModel pants = productFixture.createProduct("바지", 3_000L, 4L);
        OrderModel order = orderFacade.create(user.getId(), List.of(
            new OrderItemCommand(shirt.getId(), 2L),
            new OrderItemCommand(pants.getId(), 1L)
        ));
        List<Long> productIds = List.of(shirt.getId(), pants.getId());
        ConfirmState before = orderStateReader.confirmState(order.getId(), user.getId(), productIds);

        AtomicBoolean injectedInTransaction = new AtomicBoolean(false);
        AtomicBoolean flushCompleted = new AtomicBoolean(false);
        AtomicReference<List<Object>> seenAtInjection = new AtomicReference<>();
        doAnswer(invocation -> {
            injectedInTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            entityManager.flush();
            flushCompleted.set(true);
            seenAtInjection.set(readInCurrentTransaction(order.getId(), user.getId(), productIds));
            throw new IllegalStateException(INJECTED_FAILURE);
        }).when(orderRepository).save(any(OrderModel.class));

        assertThatThrownBy(() -> orderConfirmFacade.confirm(user.getId(), order.getId()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage(INJECTED_FAILURE);

        ConfirmState after = orderStateReader.confirmState(order.getId(), user.getId(), productIds);
        assertAll(
            () -> assertThat(injectedInTransaction).isTrue(),
            () -> assertThat(flushCompleted).as("flush 가 실패 없이 끝난 뒤 주입했다").isTrue(),
            () -> assertThat(seenAtInjection.get()).as("주입 시점 현재 트랜잭션의 DB 값")
                .isEqualTo(Arrays.asList("CONFIRMED", 7_000L, 7_000L, 3_000L, List.of(3L, 3L), 2L, 1L)),
            () -> assertThat(after).isEqualTo(before),
            () -> assertThat(after.order()).isEqualTo(Arrays.asList(user.getId(),
                OrderStatus.DRAFT, 7_000L, null, null, List.of(
                    List.of(shirt.getId(), 2L, 2_000L), List.of(pants.getId(), 1L, 3_000L)))),
            () -> assertThat(after.balance()).isEqualTo(10_000L),
            () -> assertThat(after.stocks()).containsExactly(5L, 4L),
            () -> assertThat(after.stockHistories()).isEmpty(),
            () -> assertThat(after.pointHistories()).hasSize(1)
        );
    }

    /**
     * HTTP 계층의 기술 오류 응답 연결만 확인한다. SQL 이후 rollback 의 증거는 위 Facade 테스트가 맡는다.
     */
    @DisplayName("확정 중 업무 오류가 아닌 기술 예외가 나면 HTTP 500 Internal Server Error 로 응답하고 주문·잔액·재고·이력을 바꾸지 않는다.")
    @Test
    void respondsInternalServerErrorOnTechnicalFailure() throws Exception {
        UserModel user = userFixture.createUserWithPoint();
        pointFacade.charge(user.getId(), 10_000L);
        ProductModel shirt = productFixture.createProduct("티셔츠", 2_000L, 5L);
        OrderModel order = orderFacade.create(user.getId(), List.of(new OrderItemCommand(shirt.getId(), 2L)));
        List<Long> productIds = List.of(shirt.getId());
        ConfirmState before = orderStateReader.confirmState(order.getId(), user.getId(), productIds);
        doThrow(new IllegalStateException(INJECTED_FAILURE)).when(orderRepository).save(any(OrderModel.class));

        mockMvc.perform(post("/api/v1/orders/" + order.getId() + "/confirm")
                .header("X-USER-ID", String.valueOf(user.getId())))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.meta.result").value("FAIL"))
            .andExpect(jsonPath("$.meta.errorCode").value("Internal Server Error"))
            .andExpect(jsonPath("$.data").doesNotExist());

        assertThat(orderStateReader.confirmState(order.getId(), user.getId(), productIds)).isEqualTo(before);
    }
}
