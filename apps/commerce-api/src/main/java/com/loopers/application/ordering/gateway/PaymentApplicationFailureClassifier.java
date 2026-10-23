package com.loopers.application.ordering.gateway;

/** DB 구현의 오류 코드 식별만 담당한다. TX rollback 확인은 호출자가 별도로 수행한다. */
public interface PaymentApplicationFailureClassifier {
    boolean isDeadlock(Throwable failure);
}
