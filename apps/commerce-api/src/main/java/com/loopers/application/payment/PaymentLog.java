package com.loopers.application.payment;

import com.loopers.domain.payment.CreateObservation;
import com.loopers.domain.payment.Payment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class PaymentLog {

    void created(Payment payment, CreateObservation observation) {
        String result = CreateResult.nameOf(observation);
        if (observation instanceof CreateObservation.CreateUnknown) {
            log.warn("PG 생성 호출의 결과를 모른다. 다시 보내지 않고 조회로 확인한다: paymentId={}, orderId={}, result={}",
                payment.getId(), payment.getOrderId(), result);
            return;
        }
        log.info("PG 생성 호출이 끝났다: paymentId={}, orderId={}, result={}",
            payment.getId(), payment.getOrderId(), result);
    }
}
