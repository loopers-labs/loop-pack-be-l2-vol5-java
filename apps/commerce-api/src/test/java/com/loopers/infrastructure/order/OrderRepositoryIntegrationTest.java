package com.loopers.infrastructure.order;

import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderQuantity;
import com.loopers.domain.order.OrderRepository;
import com.loopers.domain.product.Price;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class OrderRepositoryIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-10-30T00:00:00Z");

    private final OrderRepository orderRepository;
    private final TransactionTemplate transactionTemplate;
    private final DatabaseCleanUp databaseCleanUp;

    @Autowired
    OrderRepositoryIntegrationTest(OrderRepository orderRepository, TransactionTemplate transactionTemplate,
                                   DatabaseCleanUp databaseCleanUp) {
        this.orderRepository = orderRepository;
        this.transactionTemplate = transactionTemplate;
        this.databaseCleanUp = databaseCleanUp;
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("EVT-006: 나노초가 있는 시각으로 확정해도, 다시 읽은 확정 시각이 메모리의 값과 같다")
    @Test
    void confirmedAtSurvivesRoundTrip() {
        Order draft = Order.draft(1L, List.of(OrderItem.of(10L, "코트", Price.of(3_000), OrderQuantity.of(2))), NOW);
        draft.startPayment();
        Long orderId = orderRepository.save(draft).getId();

        Order confirmed = transactionTemplate.execute(status -> {
            Order pending = orderRepository.findById(orderId).orElseThrow();
            pending.completePayment(Instant.parse("2026-10-30T01:02:03.123456789Z"));
            orderRepository.save(pending);
            return pending;
        });
        Order reloaded = orderRepository.findById(orderId).orElseThrow();

        assertThat(reloaded.getConfirmedAt()).isEqualTo(confirmed.getConfirmedAt());
    }
}
