package com.loopers.domain.order;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

public record StockReservation(long orderId, long productId, int quantity) {
    public StockReservation {
        if (orderId <= 0 || productId <= 0 || quantity <= 0) {
            throw new CoreException(ErrorType.INVALID_REQUEST);
        }
    }
}
