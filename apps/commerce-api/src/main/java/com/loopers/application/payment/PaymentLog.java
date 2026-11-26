package com.loopers.application.payment;

import com.loopers.domain.payment.CreateObservation;
import com.loopers.domain.payment.Payment;
import com.loopers.domain.payment.PaymentStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class PaymentLog {

    void created(Payment payment, CreateObservation observation) {
        if (observation instanceof CreateObservation.Accepted) {
            return;
        }
        String result = CreateResult.nameOf(observation);
        if (observation instanceof CreateObservation.CreateUnknown) {
            log.warn("PG 생성 호출의 결과를 모른다. 다시 보내지 않고 조회로 확인한다: paymentId={}, orderId={}, result={}",
                payment.getId(), payment.getOrderId(), result);
            return;
        }
        log.info("PG 생성 호출이 접수되지 않았다: paymentId={}, orderId={}, result={}",
            payment.getId(), payment.getOrderId(), result);
    }

    void changed(PaymentStatus from, Payment payment, String source) {
        if (payment.getStatus() == from) {
            return;
        }
        if (payment.getStatus() == PaymentStatus.CONFLICT) {
            log.warn("결제가 PG 와 어긋난 상태로 바뀌었다: paymentId={}, orderId={}, source={}, from={}, to={}",
                payment.getId(), payment.getOrderId(), source, from, payment.getStatus());
            return;
        }
        log.info("결제 상태가 바뀌었다: paymentId={}, orderId={}, source={}, from={}, to={}",
            payment.getId(), payment.getOrderId(), source, from, payment.getStatus());
    }

    void applyFailed(Payment payment, String source, Exception e) {
        log.warn("조회 결과를 결제에 반영하지 못했다: paymentId={}, orderId={}, source={}, error={}",
            payment.getId(), payment.getOrderId(), source, e.getClass().getSimpleName(), e);
    }
}
