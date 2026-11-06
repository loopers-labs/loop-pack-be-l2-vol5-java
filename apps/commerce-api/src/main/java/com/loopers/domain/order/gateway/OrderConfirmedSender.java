package com.loopers.domain.order.gateway;

import com.loopers.domain.order.vo.OrderConfirmedSendResult;

/** 저장된 key · payload 를 바이트 그대로 보냄. 결과를 기다려 분류해 돌려주고 예외를 던지지 않음 (7주차 D-104, D-107) */
public interface OrderConfirmedSender {

    OrderConfirmedSendResult send(String messageKey, String payload);
}
