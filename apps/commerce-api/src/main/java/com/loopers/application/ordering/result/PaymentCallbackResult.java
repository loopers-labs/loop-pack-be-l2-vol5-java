package com.loopers.application.ordering.result;

// 처리 완료는 결제 성공과 다르다. 고객 주문 상세는 callback 응답에 포함하지 않는다.
public record PaymentCallbackResult(boolean completed, boolean attentionRequired) {}
