package com.loopers.application.payment;

// PG 주문 식별자의 무작위 부분을 만든다. 테스트는 구현을 교체해 값을 미리 정한다.
public interface ProviderOrderSuffixGenerator {

    String next();
}
