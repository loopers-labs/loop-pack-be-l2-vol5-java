package com.loopers.domains.order.app;

import com.loopers.domains.order.app.port.PaymentApplyFailureCounter;
import com.loopers.domains.order.app.port.PaymentApplyFailureCounter.Source;
import com.loopers.domains.payment.app.port.PaymentAnomalyRepository;
import com.loopers.domains.payment.app.port.PaymentAnomalyRepository.Kind;
import java.time.Clock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

// 결과 반영 트랜잭션이 rollback되면 그 트랜잭션에는 실패 사실을 남길 수 없다. 판단 근거가 로그 부재에 기대지 않도록
// rollback 뒤 별도 짧은 트랜잭션으로 APPLY_FAILED 이상을 남긴다(w10s7). 호출부는 반영 트랜잭션이 끝난 뒤 부르므로
// 주문 행 잠금을 쥔 채 기록하지 않는다. 기록 실패는 원래 실패를 가리지 않도록 ERROR만 남기고 삼킨다.
@Slf4j
@Component
public class PaymentApplyFailureRecorder {
    private final PaymentAnomalyRepository paymentAnomalyRepository;
    private final PaymentApplyFailureCounter paymentApplyFailureCounter;
    private final TransactionTemplate requiresNew;
    private final Clock clock;

    public PaymentApplyFailureRecorder(PaymentAnomalyRepository paymentAnomalyRepository,
        PaymentApplyFailureCounter paymentApplyFailureCounter, PlatformTransactionManager transactionManager, Clock clock) {
        this.paymentAnomalyRepository = paymentAnomalyRepository;
        this.paymentApplyFailureCounter = paymentApplyFailureCounter;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    // summary에는 예외 원문을 넣지 않는다. 원문에는 SQL·값이 섞일 수 있어 종류와 경로만 남긴다.
    public void record(long orderId, Source source, RuntimeException failure) {
        String causeType = failure.getClass().getSimpleName();
        try {
            requiresNew.executeWithoutResult(status -> paymentAnomalyRepository.record(orderId, Kind.APPLY_FAILED,
                "causeType=" + causeType + ", source=" + source, clock.instant()));
        } catch (RuntimeException recordFailure) {
            log.error("결제 반영 실패 이상 기록 실패. orderId={}, source={}, applyCauseType={}, causeType={}", orderId, source,
                causeType, recordFailure.getClass().getSimpleName());
            return;
        }
        // 이상 행이 commit된 뒤에만 센다. 기록이 rollback되면 DB와 counter가 어긋나지 않게 세지 않는다.
        paymentApplyFailureCounter.record(source);
    }
}
