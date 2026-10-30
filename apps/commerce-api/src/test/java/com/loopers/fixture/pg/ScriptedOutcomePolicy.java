package com.loopers.fixture.pg;

import com.loopers.pgsimulator.CreateAttempt;
import com.loopers.pgsimulator.OutcomePolicy;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * 테스트가 등록한 생성 결과를 orderId 별 FIFO 로 PG 에 알려 준다(5.7). PG 는 헤더·입력 검증을 통과한 생성 요청에서만
 * 이 정책을 호출하므로, 프록시가 400 이 아닌 응답에서만 reply 를 꺼내는 것과 같은 요청에서 한 칸씩 소비된다.
 * 비어 있으면 PENDING 이다. 자동 처리를 끈 PG 이므로 PENDING_THEN_SUCCESS 여도 상태는 PENDING 에 머문다.
 */
final class ScriptedOutcomePolicy implements OutcomePolicy {

    private final Map<String, Deque<CreateOutcome>> outcomes = new HashMap<>();

    synchronized void enqueue(String orderId, CreateOutcome outcome) {
        outcomes.computeIfAbsent(orderId, id -> new ArrayDeque<>()).addLast(outcome);
    }

    @Override
    public synchronized com.loopers.pgsimulator.CreateOutcome decide(CreateAttempt attempt) {
        Deque<CreateOutcome> queue = outcomes.get(attempt.orderId());
        CreateOutcome outcome = queue == null || queue.isEmpty() ? CreateOutcome.PENDING : queue.pollFirst();
        return switch (outcome) {
            case PENDING -> com.loopers.pgsimulator.CreateOutcome.PENDING_THEN_SUCCESS;
            case SUCCESS -> com.loopers.pgsimulator.CreateOutcome.IMMEDIATE_SUCCESS;
            case REJECT_BEFORE_CREATE -> com.loopers.pgsimulator.CreateOutcome.REJECT_BEFORE_CREATE;
        };
    }

    synchronized void clear() {
        outcomes.clear();
    }
}
