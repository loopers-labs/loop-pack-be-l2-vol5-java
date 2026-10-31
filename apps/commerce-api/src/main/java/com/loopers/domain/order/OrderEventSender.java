package com.loopers.domain.order;

import java.util.List;

public interface OrderEventSender {

    List<OrderEventDelivery> send(List<PendingOrderEvent> events);
}
