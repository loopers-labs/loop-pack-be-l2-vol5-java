package com.loopers.support.outbox;

import com.loopers.application.order.dto.OrderConfirmedInfo;
import com.loopers.application.order.event.OrderConfirmedOutboxRecorder;
import com.loopers.contract.order.OrderConfirmed;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderService;
import com.loopers.domain.order.OrderStatus;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 테스트용 복구 도구: Outbox 없이 확정된 주문(W6 누락)에, 저장된 확정 사실로 같은 사건의 Outbox 를 채운다.
 * 실제로 복구할 운영 데이터가 없어 테스트에 둔다. 생성 규칙은 운영의 {@link OrderConfirmedOutboxRecorder} 를 그대로 쓴다.
 */
public class MissingOutboxRecovery {

    private final OrderService orderService;
    private final OrderConfirmedOutboxRecorder recorder;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public MissingOutboxRecovery(
        OrderService orderService,
        OrderConfirmedOutboxRecorder recorder,
        JdbcTemplate jdbcTemplate,
        PlatformTransactionManager transactionManager,
        Clock clock
    ) {
        this.orderService = orderService;
        this.recorder = recorder;
        this.jdbcTemplate = jdbcTemplate;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** 확정 사실이 부족하거나, 이미 있는 Outbox 가 주문 사실과 다르면 추측하지 않고 CONFLICT 로 거절한다. */
    public void recover(Long orderId) {
        transaction.executeWithoutResult(status -> {
            Order order = orderService.get(orderId);
            if (order.getStatus() != OrderStatus.CONFIRMED || order.getConfirmedAt() == null) {
                throw new CoreException(ErrorType.CONFLICT, "확정 사실이 부족해 복구할 수 없습니다. (주문 ID: " + orderId + ")");
            }
            OrderConfirmedInfo facts = OrderConfirmedInfo.from(order);
            List<byte[]> existing = jdbcTemplate.query(
                "select payload from outbox where event_id = ?", (rs, rowNum) -> rs.getBytes("payload"), facts.eventId()
            );
            if (existing.isEmpty()) {
                recorder.record(order, ZonedDateTime.now(clock));
                return;
            }
            if (!isSameFacts(existing.get(0), facts)) {
                throw new CoreException(ErrorType.CONFLICT, "이미 있는 Outbox 가 주문 사실과 다릅니다. (eventId: " + facts.eventId() + ")");
            }
        });
    }

    private static boolean isSameFacts(byte[] payload, OrderConfirmedInfo facts) {
        OrderConfirmed stored;
        try {
            stored = OrderConfirmed.getDecoder().decode(payload);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Set<String> storedItems = stored.getItems().stream()
            .map(item -> item.getProductId() + "x" + item.getQuantity())
            .collect(Collectors.toSet());
        Set<String> orderItems = facts.items().stream()
            .map(item -> item.productId() + "x" + item.quantity())
            .collect(Collectors.toSet());
        return stored.getOrderId() == facts.orderId()
            && stored.getConfirmedAt().equals(facts.confirmedAt().toInstant())
            && storedItems.equals(orderItems);
    }
}
