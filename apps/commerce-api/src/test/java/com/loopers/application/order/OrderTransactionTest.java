package com.loopers.application.order;

import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderRepository;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.user.UserModel;
import com.loopers.domain.user.UserService;
import com.loopers.infrastructure.order.OrderJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

// 테스트 메서드에 @Transactional을 붙이지 않는다 — 테스트 자동 rollback을 서비스 rollback으로 오인하지 않기 위해서.
@SpringBootTest
class OrderTransactionTest {

    @Autowired
    private OrderFacade orderFacade;

    @MockitoSpyBean
    private OrderRepository orderRepository;

    @Autowired
    private UserService userService;

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

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("여러 품목의 주문 확정에서 재고·포인트 차감 SQL이 DB에 나간 뒤 주문 저장이 실패하면, DRAFT·재고·잔액·결제 결과가 전부 변경 전과 같다.")
    @Test
    void rollsBackStockPointsAndConfirmation_whenOrderSaveFailsAfterDeductionsAreFlushed() {
        // arrange — 각 저장이 자기 트랜잭션으로 commit된다
        Long userId = userJpaRepository.save(new UserModel()).getId();
        userService.chargePoint(userId, 10_000L);
        Long productA = productJpaRepository.save(new ProductModel("상품A", 1_000L, 1L, 10)).getId();
        Long productB = productJpaRepository.save(new ProductModel("상품B", 2_000L, 1L, 10)).getId();
        Long orderId = orderFacade.createOrder(userId, List.of(
            new OrderItemRequest(productA, 2),
            new OrderItemRequest(productB, 1)
        )).id();

        // 주문 저장 시점에: 앞 단계의 변경을 DB로 보내고, 같은 트랜잭션 안에서 반영된 값을 기록한 뒤 실패시킨다.
        AtomicLong stockAInside = new AtomicLong(-1);
        AtomicLong balanceInside = new AtomicLong(-1);
        AtomicReference<String> statusInside = new AtomicReference<>();
        doAnswer(invocation -> {
            entityManager.flush();
            stockAInside.set(singleNumber("SELECT stock_remaining FROM product WHERE id = " + productA));
            balanceInside.set(singleNumber("SELECT point_balance FROM user WHERE id = " + userId));
            statusInside.set((String) entityManager
                .createNativeQuery("SELECT status FROM orders WHERE id = " + orderId)
                .getSingleResult());
            throw new IllegalStateException("주문 저장 실패 주입");
        }).when(orderRepository).save(any(Order.class));

        // act
        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> orderFacade.confirmOrder(orderId, userId)
        );

        // assert — 트랜잭션 안에서는 차감과 상태 전이가 DB에 반영돼 있었다(실제 SQL이 나갔다)
        assertAll(
            () -> assertThat(exception.getMessage()).isEqualTo("주문 저장 실패 주입"),
            () -> assertThat(stockAInside.get()).isEqualTo(8L),
            () -> assertThat(balanceInside.get()).isEqualTo(10_000L - (1_000L * 2 + 2_000L)),
            () -> assertThat(statusInside.get()).isEqualTo("CONFIRMED")
        );

        // assert — 트랜잭션이 끝난 뒤 새로 읽은 DB: 전부 작업 전 상태
        Order reloaded = orderJpaRepository.findById(orderId).orElseThrow();
        assertAll(
            () -> assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.DRAFT),
            () -> assertThat(reloaded.getPaidAmount()).isNull(),
            () -> assertThat(reloaded.getConfirmedAt()).isNull(),
            () -> assertThat(productJpaRepository.findById(productA).orElseThrow().getRemainingStock()).isEqualTo(10),
            () -> assertThat(productJpaRepository.findById(productB).orElseThrow().getRemainingStock()).isEqualTo(10),
            () -> assertThat(userJpaRepository.findById(userId).orElseThrow().getPoint().getBalance()).isEqualTo(10_000L)
        );
    }

    private long singleNumber(String sql) {
        return ((Number) entityManager.createNativeQuery(sql).getSingleResult()).longValue();
    }
}
