package com.loopers.domains.order.app.port;

// 전송 한 건의 결과. broker 수준 실패는 모든 행에 해당하고, 행 수준 실패는 그 행 내용 때문에 생긴 실패다(D114).
public sealed interface SendOutcome {
    record Published() implements SendOutcome {
    }

    record BrokerFailure(String error) implements SendOutcome {
    }

    record RowFailure(String error) implements SendOutcome {
    }
}
