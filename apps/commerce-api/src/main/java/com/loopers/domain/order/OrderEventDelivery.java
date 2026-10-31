package com.loopers.domain.order;

public sealed interface OrderEventDelivery {

    PendingOrderEvent event();

    record Published(PendingOrderEvent event) implements OrderEventDelivery {
    }

    record Rejected(PendingOrderEvent event, String error) implements OrderEventDelivery {
    }

    record Unconfirmed(PendingOrderEvent event, String error) implements OrderEventDelivery {
    }
}
