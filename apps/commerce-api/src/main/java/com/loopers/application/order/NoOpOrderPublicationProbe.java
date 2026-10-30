package com.loopers.application.order;

import com.loopers.domain.order.OrderConfirmed;
import org.springframework.stereotype.Component;

@Component
public class NoOpOrderPublicationProbe implements OrderPublicationProbe {

    @Override
    public void beforeSend(OrderConfirmed event) {
    }
}
