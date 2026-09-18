package com.loopers.infrastructure.order;

import com.loopers.application.order.OrderFacade;
import com.loopers.application.order.OrderInfo;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderLine;
import com.loopers.domain.order.OrderRepository;
import com.loopers.domain.order.OrderService;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.order.OrderSummary;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

@SpringBootTest
class OrderQueryIntegrationTest {

    private static final Long ME = 1L;
    private static final Long OTHER = 2L;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderFacade orderFacade;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private Order saveOrder(Long userId, String firstProductName, boolean confirmed) {
        Order order = orderRepository.save(Order.draft(userId, List.of(
            new OrderLine(10L, firstProductName, 1_000L, 1),
            new OrderLine(20L, "두 번째 품목", 2_000L, 1)
        )));
        if (confirmed) {
            transactionTemplate.executeWithoutResult(status ->
                orderRepository.findById(order.getId()).orElseThrow().confirm(3_000L, ZonedDateTime.now()));
        }
        return order;
    }

    @DisplayName("요청자와 상태로 거르고, 최근 주문 순으로 요약을 돌려준다. 요약은 트랜잭션 밖에서도 읽힌다.")
    @Test
    void filtersByUserAndStatus() {
        // arrange
        Order older = saveOrder(ME, "첫 주문", true);
        saveOrder(ME, "확정 전 주문", false);
        Order newer = saveOrder(ME, "둘째 주문", true);
        saveOrder(OTHER, "남의 주문", true);

        // act
        Page<OrderSummary> page = orderService.getOrderSummaries(ME, OrderStatus.CONFIRMED, PageRequest.of(0, 20));

        // assert
        assertAll(
            () -> assertThat(page.getContent()).extracting(OrderSummary::id).containsExactly(newer.getId(), older.getId()),
            () -> assertThat(page.getContent().get(0).itemCount()).isEqualTo(2),
            () -> assertThat(page.getContent().get(0).representativeProductName()).isEqualTo("둘째 주문"),
            () -> assertThat(page.getContent().get(0).paymentAmount()).isEqualTo(3_000L),
            () -> assertThat(page.getTotalElements()).isEqualTo(2)
        );
    }

    @DisplayName("사용자와 상태를 주지 않으면, 모든 구매자의 모든 주문을 돌려준다.")
    @Test
    void returnsAll_whenNoFilter() {
        saveOrder(ME, "내 주문", true);
        saveOrder(ME, "확정 전 주문", false);
        saveOrder(OTHER, "남의 주문", true);

        Page<OrderSummary> page = orderService.getOrderSummaries(null, null, PageRequest.of(0, 20));

        assertThat(page.getTotalElements()).isEqualTo(3);
    }

    @DisplayName("상세는 품목을 함께 읽어, 트랜잭션 밖에서도 품목을 요청 순서대로 볼 수 있다.")
    @Test
    void readsDetailWithItems() {
        // arrange
        Order order = saveOrder(ME, "첫 품목", true);

        // act
        OrderInfo info = orderFacade.getMyOrder(ME, order.getId());

        // assert
        assertAll(
            () -> assertThat(info.items()).extracting(OrderInfo.Item::productName).containsExactly("첫 품목", "두 번째 품목"),
            () -> assertThat(info.paymentAmount()).isEqualTo(3_000L)
        );
    }
}
