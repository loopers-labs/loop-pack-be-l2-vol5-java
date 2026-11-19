package com.loopers.batch.job.ranking.coordination;

import java.util.UUID;

@FunctionalInterface
public interface WeeklyPlanFinalizer {
    void finalizePlan(UUID snapshotId);
}
