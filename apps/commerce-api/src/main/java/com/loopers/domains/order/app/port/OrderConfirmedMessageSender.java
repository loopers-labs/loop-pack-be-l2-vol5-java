package com.loopers.domains.order.app.port;

public interface OrderConfirmedMessageSender {
    // 저장된 key·payload를 그대로 보내고 broker 확인까지 기다린다. 전송 기술의 예외는 결과로 바꿔 돌려준다.
    SendOutcome send(String key, String payload);
}
