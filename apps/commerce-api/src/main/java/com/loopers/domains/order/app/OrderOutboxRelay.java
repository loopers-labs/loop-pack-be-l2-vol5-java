package com.loopers.domains.order.app;

import com.loopers.domains.order.app.port.OrderConfirmedMessageSender;
import com.loopers.domains.order.app.port.OrderConfirmedOutbox;
import com.loopers.domains.order.app.port.OutboxRelaySettings;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class OrderOutboxRelay {
    private final OrderConfirmedOutbox orderConfirmedOutbox;
    private final OrderConfirmedMessageSender orderConfirmedMessageSender;
    private final OutboxRelaySettings outboxRelaySettings;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public OrderOutboxRelay(OrderConfirmedOutbox orderConfirmedOutbox, OrderConfirmedMessageSender orderConfirmedMessageSender,
        OutboxRelaySettings outboxRelaySettings, TransactionTemplate transactionTemplate, Clock clock) {
        this.orderConfirmedOutbox = orderConfirmedOutbox;
        this.orderConfirmedMessageSender = orderConfirmedMessageSender;
        this.outboxRelaySettings = outboxRelaySettings;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    public void runOnce() {
    }
}
