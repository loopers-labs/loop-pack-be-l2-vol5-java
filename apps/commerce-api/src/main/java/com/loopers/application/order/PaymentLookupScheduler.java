package com.loopers.application.order;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * 결제 요청의 백업 거래 조회. confirmOrder 가 결제 시작 커밋 뒤, 결제 요청 전에 예약한다.
 * pg.lookup-delay 뒤 PG 에 기존 거래를 조회(PaymentGateway.find)해 결과 반영(applyResult)에 넘긴다.
 * 결제 요청과 서로 기다리지 않는다: 조회는 결제 요청 결과를 보지 않고 늘 나가고, 이미 결론난 주문이면 applyResult 가 무시한다 (주문 행 락·멱등).
 * 예약은 메모리에만 있어 서버가 내려가면 사라진다.
 * 스케줄러를 빈으로 두지 않는다 (Executor 빈이 생기면 Boot 의 applicationTaskExecutor 자동 구성이 빠진다).
 */
@Slf4j
@Component
public class PaymentLookupScheduler {
    private static final int POOL_SIZE = 8;

    private final PaymentGateway paymentGateway;
    private final OrderPaymentProcessor orderPaymentProcessor;
    private final Duration delay;
    private final ThreadPoolTaskScheduler taskScheduler;

    public PaymentLookupScheduler(PaymentGateway paymentGateway, OrderPaymentProcessor orderPaymentProcessor,
                                  @Value("${pg.lookup-delay}") Duration delay) {
        this.paymentGateway = paymentGateway;
        this.orderPaymentProcessor = orderPaymentProcessor;
        this.delay = delay;
        this.taskScheduler = new ThreadPoolTaskScheduler();
        taskScheduler.setPoolSize(POOL_SIZE);
        taskScheduler.setThreadNamePrefix("pg-lookup-");
        taskScheduler.initialize();
    }

    /** 지금부터 pg.lookup-delay 뒤 이 주문의 거래 조회를 한 번 예약한다. */
    public void schedule(Long userId, Long orderId) {
        taskScheduler.getScheduledExecutor().schedule(() -> lookup(userId, orderId), delay.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void lookup(Long userId, Long orderId) {
        try {
            orderPaymentProcessor.applyResult(orderId, paymentGateway.find(userId, orderId));
        } catch (RuntimeException e) {
            log.warn("백업 거래 조회 반영 실패 [orderId = {}]", orderId, e);
        }
    }

    @PreDestroy
    void shutdown() {
        taskScheduler.shutdown();
    }
}
