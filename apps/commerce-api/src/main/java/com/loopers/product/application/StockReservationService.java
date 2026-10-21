package com.loopers.product.application;

import com.loopers.product.application.port.in.StockReservationUseCase;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class StockReservationService implements StockReservationUseCase {

    @Override
    public void reserve(Long orderId, Map<Long, Integer> quantitiesByProductId) {
    }

    @Override
    public int consume(Long orderId) {
        return 0;
    }

    @Override
    public int release(Long orderId) {
        return 0;
    }
}
