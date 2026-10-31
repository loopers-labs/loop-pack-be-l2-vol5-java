package com.loopers.domain.order;

public interface OrderEventOutbox {

    void append(OrderEvent event);
}
