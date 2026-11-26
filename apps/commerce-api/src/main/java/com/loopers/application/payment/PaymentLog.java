package com.loopers.application.payment;

import com.loopers.domain.payment.CreateObservation;
import com.loopers.domain.payment.Payment;
import com.loopers.domain.payment.PaymentStatus;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.spi.LoggingEventBuilder;
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
            about(log.atWarn(), "payment_create_unknown", payment)
                .addKeyValue("result", result)
                .log("PG 생성 호출의 결과를 모른다. 다시 보내지 않고 조회로 확인한다: paymentId={}, orderId={}, result={}",
                    payment.getId(), payment.getOrderId(), result);
            return;
        }
        about(log.atInfo(), "payment_create_not_accepted", payment)
            .addKeyValue("result", result)
            .log("PG 생성 호출이 접수되지 않았다: paymentId={}, orderId={}, result={}",
                payment.getId(), payment.getOrderId(), result);
    }

    void changed(PaymentTransitioned transition) {
        boolean conflict = transition.to() == PaymentStatus.CONFLICT;
        String source = transition.origin().label();
        (conflict ? log.atWarn() : log.atInfo())
            .addKeyValue("event", conflict ? "payment_conflict" : "payment_status_changed")
            .addKeyValue("paymentId", transition.paymentId())
            .addKeyValue("orderId", transition.orderId())
            .addKeyValue("providerOrderId", transition.providerOrderId())
            .addKeyValue("source", source)
            .addKeyValue("from", transition.from().name())
            .addKeyValue("to", transition.to().name())
            .log((conflict ? "결제가 PG 와 어긋난 상태로 바뀌었다" : "결제 상태가 바뀌었다")
                    + ": paymentId={}, orderId={}, source={}, from={}, to={}",
                transition.paymentId(), transition.orderId(), source, transition.from(), transition.to());
    }

    void applyFailed(Payment payment, PaymentTransactions.Origin origin, Exception e) {
        String source = origin.label();
        String error = e.getClass().getSimpleName();
        about(log.atWarn(), "payment_apply_failed", payment)
            .addKeyValue("source", source)
            .addKeyValue("error", error)
            .log("조회 결과를 결제에 반영하지 못했다: paymentId={}, orderId={}, source={}, error={}",
                payment.getId(), payment.getOrderId(), source, error);
    }

    private static LoggingEventBuilder about(LoggingEventBuilder line, String event, Payment payment) {
        return line
            .addKeyValue("event", event)
            .addKeyValue("paymentId", payment.getId())
            .addKeyValue("orderId", payment.getOrderId())
            .addKeyValue("providerOrderId", payment.getProviderOrderId().value());
    }
}
