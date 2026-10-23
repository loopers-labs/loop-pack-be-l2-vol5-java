package com.loopers.domain.payment;

import java.util.List;

/**
 * 같은 주문(providerOrderId)의 거래 목록 조회, 또는 transactionKey 단건 조회 1회의 관찰 결과 (설계 7-3).
 * FOUND 는 조회가 되었다는 뜻이고 목록은 0건·1건·2건 이상 모두 가능하다.
 * UNAVAILABLE 은 PG 에 물어보지 못한 경우다. 0건과 다르다 (설계 7-4·7-6). 이유는 둘이다 (설계 5-7 결정 ㉙).
 * CALL_FAILED 는 timeout·연결 실패·5xx·잘못된 JSON 처럼 호출이 실패한 것이고, CIRCUIT_OPEN 은 서킷브레이커가 호출 자체를 막은 것이다.
 * 스케줄러는 CIRCUIT_OPEN 을 조회 횟수에 세지 않고 그 주기를 중단한다. reconcile·callback 은 둘을 구분하지 않는다.
 */
public record PaymentLookupResult(Outcome outcome, UnavailableReason reason, List<PaymentTransaction> transactions) {

    public enum Outcome {
        FOUND, UNAVAILABLE
    }

    public enum UnavailableReason {
        CALL_FAILED, CIRCUIT_OPEN
    }

    public static PaymentLookupResult found(List<PaymentTransaction> transactions) {
        return new PaymentLookupResult(Outcome.FOUND, null, List.copyOf(transactions));
    }

    public static PaymentLookupResult unavailable(UnavailableReason reason) {
        return new PaymentLookupResult(Outcome.UNAVAILABLE, reason, List.of());
    }

    public boolean isUnavailable() {
        return outcome == Outcome.UNAVAILABLE;
    }

    public boolean isCircuitOpen() {
        return reason == UnavailableReason.CIRCUIT_OPEN;
    }
}