package com.loopers.interfaces.api.order;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.loopers.application.order.OrderPaymentAdminInfo;

/** Keep the existing flat admin order representation while adding the same payment fields as the customer. */
public record AdminOrderPaymentResponse(@JsonUnwrapped OrderPaymentResponse order, String userId) {
    public static AdminOrderPaymentResponse from(OrderPaymentAdminInfo info) {
        return new AdminOrderPaymentResponse(OrderPaymentResponse.from(info.order()), info.userId());
    }
}
