package com.loopers.application.order;

public class OrderNotFoundException extends RuntimeException {
    public OrderNotFoundException() { super("주문을 찾을 수 없습니다."); }
}
