package com.loopers.application.order;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.ZonedDateTime;

/**
 * 결제 대기 정리. pg.payment-expiry-interval 마다, 결제 시작 뒤 pg.payment-expiry 가 지나도 AWAITING_PAYMENT 인 주문을 FAILED + 재고 반환한다.
 * 결제 요청·백업 거래 조회가 결론내지 못한 주문(서버 다운, 결과 모름)을 거둔다. 한 번에 pg.payment-expiry-batch-size 건까지, 주문마다 트랜잭션 하나.
 * 서버가 여러 대여도 SKIP LOCKED 라 서로 기다리지 않고 나눠 처리한다.
 */
@Slf4j
@Component
public class PaymentExpiryJob {
    private final OrderPaymentProcessor orderPaymentProcessor;
    private final Duration expiry;
    private final int batchSize;

    public PaymentExpiryJob(OrderPaymentProcessor orderPaymentProcessor,
                            @Value("${pg.payment-expiry}") Duration expiry,
                            @Value("${pg.payment-expiry-batch-size}") int batchSize) {
        this.orderPaymentProcessor = orderPaymentProcessor;
        this.expiry = expiry;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${pg.payment-expiry-interval}", initialDelayString = "${pg.payment-expiry-interval}")
    public void expire() {
        ZonedDateTime startedBefore = ZonedDateTime.now().minus(expiry);
        int expired = 0;
        while (expired < batchSize && orderPaymentProcessor.expireOne(startedBefore)) {
            expired++;
        }
        if (expired > 0) {
            log.info("결제 대기 만료 처리 [count = {}, startedBefore = {}]", expired, startedBefore);
        }
    }
}
