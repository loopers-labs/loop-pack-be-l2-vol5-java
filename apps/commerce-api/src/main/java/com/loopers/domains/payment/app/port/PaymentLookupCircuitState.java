package com.loopers.domains.payment.app.port;

public interface PaymentLookupCircuitState {
    // 그 PG의 조회 회로가 열려 있고 열림 대기 시간이 아직 남았으면 true다. 대기 시간이 지났으면 다음 조회가 탐색 호출이 되므로 false다.
    boolean blocksLookup(String providerId);
}
