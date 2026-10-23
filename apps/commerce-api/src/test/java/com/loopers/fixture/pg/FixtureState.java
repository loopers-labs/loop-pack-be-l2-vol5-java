package com.loopers.fixture.pg;

import java.util.List;

/**
 * 각 구성 요소를 따로 읽은 값이라 요청·콜백 처리 중이면 서로 같은 시점이라는 보장은 없다.
 * counts 는 journal·callbacks 와 같은 snapshot 에서 계산한다.
 * 결과 확인은 관련 요청·콜백 작업이 끝난 뒤(activeRequests=0, activeCallbackJobs=0) 한다.
 */
public record FixtureState(
    List<TransactionSnapshot> transactions,
    RequestCounts counts,
    List<JournalEntry> journal,
    int activeRequests,
    List<GateSnapshot> gates,
    List<CallbackRecord> callbacks,
    int activeCallbackJobs
) {

    public FixtureState {
        transactions = List.copyOf(transactions);
        journal = List.copyOf(journal);
        gates = List.copyOf(gates);
        callbacks = List.copyOf(callbacks);
    }
}
