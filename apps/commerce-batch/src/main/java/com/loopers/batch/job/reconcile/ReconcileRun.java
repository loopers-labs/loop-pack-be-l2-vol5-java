package com.loopers.batch.job.reconcile;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

// 한 번의 대사 실행(step)이 공유하는 구간·관찰값. 요약 한 줄과 워터마크 갱신이 같은 값을 보게 step마다 하나 만든다.
public class ReconcileRun {
    private final ReconcileWindow window;
    private final Optional<ReconcileCheckpoint> before;
    private final long confirmedOrders;
    private final List<Long> missingOrderIds = new ArrayList<Long>();
    private int sentCommands;

    public ReconcileRun(ReconcileWindow window, Optional<ReconcileCheckpoint> before, long confirmedOrders) {
        this.window = window;
        this.before = before;
        this.confirmedOrders = confirmedOrders;
    }

    public ReconcileWindow window() {
        return window;
    }

    public Optional<ReconcileCheckpoint> before() {
        return before;
    }

    public long confirmedOrders() {
        return confirmedOrders;
    }

    public List<Long> missingOrderIds() {
        return List.copyOf(missingOrderIds);
    }

    public int sentCommands() {
        return sentCommands;
    }

    void found(List<Long> orderIds) {
        missingOrderIds.addAll(orderIds);
    }

    void sent(int count) {
        sentCommands += count;
    }
}
