package com.loopers.domains.order.app.port;

// 결제 진행 중 사건을 세는 포트다. 호출부는 commit 뒤에만 부른다.
public interface PaymentProgressRecorder {
    void started(String providerId);

    void unknownEntered(String providerId);
}
