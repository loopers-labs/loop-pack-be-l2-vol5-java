package com.loopers.fixture.pg;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** operation+target 별 plan FIFO. 소비는 잠금 안에서 한 번에 하나만 꺼내므로 같은 plan 이 두 요청에 쓰이지 않는다. */
final class ScenarioRegistry {

    private final Map<PlanKey, Deque<ResponsePlan>> plans = new HashMap<>();

    synchronized void enqueue(PgOperation operation, String target, ResponsePlan plan) {
        plans.computeIfAbsent(new PlanKey(operation, target), key -> new ArrayDeque<>()).addLast(plan);
    }

    synchronized Optional<ResponsePlan> poll(PgOperation operation, String target) {
        Deque<ResponsePlan> queue = plans.get(new PlanKey(operation, target));
        return queue == null ? Optional.empty() : Optional.ofNullable(queue.pollFirst());
    }

    synchronized void clear() {
        plans.clear();
    }

    private record PlanKey(PgOperation operation, String target) {
    }
}
