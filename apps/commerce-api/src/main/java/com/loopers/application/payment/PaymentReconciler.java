package com.loopers.application.payment;

import com.loopers.domain.payment.PaymentObservation;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 미종결 결제를 PG에 물어 결과를 반영한다 — confirm의 바로 재조회(ⓐ), 고객 reconcile, 회복 작업(수신함·스케줄러)이 같이 쓴다
 * (docs/week5/design.md D-005). 트랜잭션이 없다. 조회는 트랜잭션 밖에서 하고, 반영만 TX B(PaymentResultApplier)로 넘긴다.
 *
 * - 거래 키를 알면 단건, 모르면(요청됨·UNKNOWN) 주문 식별자로 목록을 조회한다(pg-contract.md 3번).
 * - 1건이면 반영한다. 반영 쪽이 식별·금액을 검증하고 어긋나면 충돌로 기록한다.
 * - 2건 이상이면 고르지 않고 충돌로 기록한다(D-010).
 * - 0건·404·물어보지 못함은 실패의 증거가 아니라 아무것도 바꾸지 않는다(INV-004). create는 다시 보내지 않는다(INV-005).
 */
@RequiredArgsConstructor
@Component
public class PaymentReconciler {

    private final PaymentGateway paymentGateway;
    private final PaymentResultApplier paymentResultApplier;

    public Outcome reconcile(Long orderId, String providerOrderId, String transactionKey, LookupRetry retry) {
        List<PaymentObservation> observations;
        try {
            observations = transactionKey == null
                ? paymentGateway.findByProviderOrderId(providerOrderId, retry)
                : paymentGateway.findByTransactionKey(transactionKey, retry).stream().toList();
        } catch (PaymentGatewayUnavailableException e) {
            return Outcome.UNAVAILABLE;
        }
        if (observations.isEmpty()) {
            return Outcome.NOT_FOUND;
        }
        if (observations.size() > 1) {
            paymentResultApplier.recordMultipleTransactions(orderId);
            return Outcome.MULTIPLE_TRANSACTIONS;
        }
        paymentResultApplier.apply(orderId, observations.get(0));
        return Outcome.APPLIED;
    }

    /** 조회 결과의 종류. 상태를 바꿨는지가 아니라 PG에 물어봤는지를 말한다 — UNAVAILABLE만 "물어보지 못함"이다. */
    public enum Outcome {
        /** 거래 한 건을 반영 경계에 넘겼다(전이·변화 없음·충돌 기록 중 하나). */
        APPLIED,
        /** 거래가 없다(404·빈 목록). 실패의 증거가 아니다. */
        NOT_FOUND,
        /** 거래가 2건 이상이라 충돌로 기록했다. */
        MULTIPLE_TRANSACTIONS,
        /** PG에 물어보지 못했다(timeout·5xx·깨진 응답). */
        UNAVAILABLE
    }
}
