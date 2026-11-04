package com.loopers.application.pay.gateway;

/** 자동 조회의 단조 시간·지터와 접수 거절 대기에만 사용한다. */
public interface PaymentRecoveryRuntime {
    long nanoTime();
    double randomUnit();
    void pause(long millis) throws InterruptedException;
}
