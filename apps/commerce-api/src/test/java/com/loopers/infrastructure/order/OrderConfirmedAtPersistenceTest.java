package com.loopers.infrastructure.order;

import com.loopers.application.order.OrderConfirmedPayloadFactory;
import com.loopers.application.order.port.OrderRepository;
import com.loopers.domain.common.Money;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.Quantity;
import com.loopers.domain.product.ProductId;
import com.loopers.utils.DatabaseCleanUp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

// 저장과 재조회는 어댑터 메서드마다 별도 트랜잭션이라 1차 캐시 없이 DB 값을 다시 읽는다.
@SpringBootTest
class OrderConfirmedAtPersistenceTest {
    private static final Instant CONFIRMED_AT = Instant.parse("2026-10-29T01:00:00.123456789Z");
    private static final Instant LATER = Instant.parse("2026-10-29T02:00:00Z");

    @Autowired private OrderRepository orderRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DatabaseCleanUp cleanup;

    @AfterEach
    void clean() {
        jdbcTemplate.update("delete from order_items");
        cleanup.truncateAllTables();
    }

    @Test
    @DisplayName("처음 확정한 주문을 저장하면 확정 시각이 밀리초로 저장되고, 다시 읽은 주문으로 만든 payload가 저장 직후 payload와 같다")
    void storesConfirmedAtAndRebuildsSamePayload() {
        Order saved = confirm(orderRepository.save(Order.create(1, List.of(item(8, 1), item(7, 2)))));

        Order reloaded = orderRepository.findById(saved.getId()).orElseThrow();

        assertThat(reloaded.getConfirmedAt()).isEqualTo(Instant.parse("2026-10-29T01:00:00.123Z"));
        // DB에는 UTC로 저장한다(jpa.yml hibernate.jdbc.time_zone=UTC).
        assertThat(confirmedAtColumn(saved.getId())).isEqualTo(LocalDateTime.parse("2026-10-29T01:00:00.123"));
        assertThat(OrderConfirmedPayloadFactory.create(reloaded)).isEqualTo(OrderConfirmedPayloadFactory.create(saved));
    }

    @Test
    @DisplayName("확정된 주문에 성공이 다시 반영돼 저장돼도 저장된 확정 시각은 그대로다")
    void keepsConfirmedAtOnRepeatedSuccess() {
        Order saved = confirm(orderRepository.save(Order.create(1, List.of(item(7, 2)))));
        Order reloaded = orderRepository.findById(saved.getId()).orElseThrow();

        reloaded.applyPaymentOutcome(Order.PaymentOutcome.SUCCEEDED, LATER);
        orderRepository.save(reloaded);

        assertThat(orderRepository.findById(saved.getId()).orElseThrow().getConfirmedAt())
            .isEqualTo(Instant.parse("2026-10-29T01:00:00.123Z"));
    }

    @Test
    @DisplayName("확정 전 주문과 확정 시각 없이 CONFIRMED인 옛 주문은 확정 시각이 비어 있다")
    void keepsNullForUnconfirmedAndLegacyOrders() {
        Order draft = orderRepository.save(Order.create(1, List.of(item(7, 2))));
        Order legacy = orderRepository.save(Order.create(1, List.of(item(8, 1))));
        jdbcTemplate.update("update orders set status = 'CONFIRMED' where id = ?", legacy.getId());

        assertThat(orderRepository.findById(draft.getId()).orElseThrow().getConfirmedAt()).isNull();
        Order legacyReloaded = orderRepository.findById(legacy.getId()).orElseThrow();
        assertThat(legacyReloaded.getStatus()).isEqualTo(Order.Status.CONFIRMED);
        assertThat(legacyReloaded.getConfirmedAt()).isNull();
    }

    private Order confirm(Order draft) {
        draft.startPayment();
        Order pending = orderRepository.save(draft);
        pending.applyPaymentOutcome(Order.PaymentOutcome.SUCCEEDED, CONFIRMED_AT);
        return orderRepository.save(pending);
    }

    private LocalDateTime confirmedAtColumn(long orderId) {
        return jdbcTemplate.queryForObject("select confirmed_at from orders where id = ?", LocalDateTime.class, orderId);
    }

    private static OrderItem item(long productId, int quantity) {
        return new OrderItem(new ProductId(productId), new Quantity(quantity), new Money(1000));
    }
}
