package com.loopers.application.order;

import com.loopers.domain.payment.PaymentAttempt;
import com.loopers.domain.payment.PaymentAttemptRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * 만든 지 10분이 지났는데 주문이 아직 PAYMENT_PENDING인 결제 시도를 reconcile과 같은 규칙으로 마무리한다.
 * 결제 생성 POST는 다시 보내지 않는다. 시간이 오래 지났어도 조회 0건·PENDING·조회 실패에는 상태와 예약을 유지한다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentRecoveryBatch {

    static final Duration MIN_AGE = Duration.ofMinutes(10);
    static final int PAGE_SIZE = 50;

    private final PaymentAttemptRepository attempts;
    private final OrderFacade orderFacade;
    private final Clock clock;

    public Result run() {
        ZonedDateTime now = ZonedDateTime.now(clock);
        ZonedDateTime cutoff = now.minus(MIN_AGE);
        long cursor = 0;
        int finished = 0;
        int unfinished = 0;
        int failed = 0;
        while (true) {
            List<PaymentAttempt> page = attempts.findUnfinishedCreatedBefore(cutoff, cursor, PAGE_SIZE);
            if (page.isEmpty()) {
                return new Result(finished, unfinished, failed);
            }
            for (PaymentAttempt attempt : page) {
                try {
                    OrderPaymentInfo payment = orderFacade.recover(attempt.getOrderId());
                    if (payment.isFinished()) {
                        finished++;
                    } else {
                        unfinished++;
                    }
                } catch (RuntimeException exception) {
                    failed++;
                    log.error("Payment recovery failed: orderId={}", attempt.getOrderId(), exception);
                }
                cursor = attempt.getId();
            }
        }
    }

    /** finished: 결제·주문 반영까지 끝남, unfinished: 아직 결과를 모르거나 확인하지 못함, failed: 처리 중 예외. */
    public record Result(int finished, int unfinished, int failed) {}
}
