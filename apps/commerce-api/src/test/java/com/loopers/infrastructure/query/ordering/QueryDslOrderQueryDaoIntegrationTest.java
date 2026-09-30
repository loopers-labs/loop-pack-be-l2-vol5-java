package com.loopers.infrastructure.query.ordering;

import static org.assertj.core.api.Assertions.assertThat;

import com.loopers.application.common.PageCriteria;
import com.loopers.application.common.PageResult;
import com.loopers.application.ordering.query.AdminOrderView;
import com.loopers.application.ordering.query.OrderQueryDao;
import com.loopers.application.ordering.query.OrderView;
import com.loopers.domain.ordering.model.Order;
import com.loopers.domain.ordering.model.OrderItem;
import com.loopers.domain.ordering.repository.OrderRepository;
import com.loopers.support.test.IntegrationTest;
import com.loopers.utils.DatabaseCleanUp;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

@IntegrationTest
class QueryDslOrderQueryDaoIntegrationTest {
    @Autowired
    private OrderQueryDao orderQueryDao;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private JdbcClient jdbcClient;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("내 주문 목록")
    @Test
    void returnsOrders_orderedByCreatedAtDescending() {
        Order older = orderRepository.save(order(1L));
        Order newer = orderRepository.save(order(1L));
        setCreatedAt(older.getId(), "2026-01-01 00:00:00");
        setCreatedAt(newer.getId(), "2026-01-02 00:00:00");

        PageResult<OrderView> result = orderQueryDao.findOrders(1L, new PageCriteria(0, 20));

        assertThat(result.totalElements()).isEqualTo(2);
        assertThat(result.items()).extracting(OrderView::orderId).containsExactly(newer.getId(), older.getId());
        OrderView view = result.items().get(0);
        assertThat(view.items()).hasSize(1);
        assertThat(view.items().get(0).productName()).isEqualTo("상품");
        assertThat(view.paymentAmount()).isNull();
        assertThat(view.paymentStatus()).isNull();
    }

    @DisplayName("다른 사용자의 주문은 목록에서 제외한다")
    @Test
    void excludesOtherUsersOrders() {
        orderRepository.save(order(1L));
        orderRepository.save(order(2L));

        PageResult<OrderView> result = orderQueryDao.findOrders(1L, new PageCriteria(0, 20));

        assertThat(result.totalElements()).isEqualTo(1);
    }

    @DisplayName("결제 완료된 주문은 결제 금액·상태를 함께 반환한다")
    @Test
    void includesPaymentFields_whenOrderRecordExists() {
        Order saved = orderRepository.save(order(1L));
        saved.confirm();
        orderRepository.save(saved);

        OrderView view = orderQueryDao.findOrder(saved.getId()).orElseThrow();

        assertThat(view.paymentAmount()).isEqualTo(saved.getTotalAmount());
        assertThat(view.paymentStatus().name()).isEqualTo("PAID");
    }

    @DisplayName("없는 주문은 빈 결과를 반환한다")
    @Test
    void returnsEmpty_whenOrderDoesNotExist() {
        Optional<OrderView> result = orderQueryDao.findOrder(999L);

        assertThat(result).isEmpty();
    }

    @DisplayName("관리자 주문 목록은 모든 구매자의 주문에 구매자 ID를 포함한다")
    @Test
    void returnsAllUsersOrders_withUserId_forAdmin() {
        orderRepository.save(order(1L));
        orderRepository.save(order(2L));

        PageResult<AdminOrderView> result = orderQueryDao.findAdminOrders(new PageCriteria(0, 20));

        assertThat(result.totalElements()).isEqualTo(2);
        assertThat(result.items()).extracting(AdminOrderView::userId).containsExactlyInAnyOrder(1L, 2L);
    }

    private Order order(long userId) {
        return Order.create(userId, List.of(OrderItem.create(1L, "상품", 1_000L, 2)));
    }

    private void setCreatedAt(long orderId, String createdAt) {
        jdbcClient.sql("UPDATE orders SET created_at = :createdAt WHERE id = :orderId")
            .param("createdAt", createdAt)
            .param("orderId", orderId)
            .update();
    }
}
