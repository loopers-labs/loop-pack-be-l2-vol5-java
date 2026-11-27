package com.loopers.interfaces.scheduler;

import com.loopers.application.payment.PaymentUnresolvedMetrics;
import com.loopers.application.payment.PaymentUnresolvedMetricsProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 미종결 결제 지표 집계 tick (10주차 설계 3-1-2).
 * 기본 taskScheduler 를 그대로 쓴다. 전용 스레드 풀을 떼지 않는 대신, 집계가 밀리면 값이 틀리는 게 아니라 낡고
 * 그 낡음은 마지막 집계 성공 시각 지표로 드러난다.
 * fixedDelay 라 이전 집계가 끝난 뒤 간격을 세므로 느린 집계가 작업을 쌓지 않는다.
 */
@RequiredArgsConstructor
@Component
public class PaymentUnresolvedMetricsScheduler {

    private final PaymentUnresolvedMetrics paymentUnresolvedMetrics;
    private final PaymentUnresolvedMetricsProperties properties;

    @Scheduled(fixedDelayString = "${payment.metrics.unresolved.fixed-delay}")
    public void run() {
        if (!properties.enabled()) {
            return;
        }
        paymentUnresolvedMetrics.refresh();
    }
}
