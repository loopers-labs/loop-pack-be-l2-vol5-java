package com.loopers.domains.order.app;

import com.loopers.common.error.NotFoundException;

public class OrderNotFoundException extends NotFoundException {
    public OrderNotFoundException() { super("주문을 찾을 수 없습니다."); }
}
