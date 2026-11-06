package com.loopers.domain.order.gateway;

import com.loopers.domain.order.vo.OrderConfirmed;

/** 메시지 형식은 infrastructure 가 소유함. 확정 TX 안에서 부르므로 실패하면 확정도 rollback 됨 (7주차 D-102) */
public interface OrderConfirmedSerializer {
    String serialize(OrderConfirmed event);
}
