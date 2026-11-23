package com.loopers.batch.job.ranking.step;

import com.loopers.batch.job.ranking.WeeklyRankingParameters;
import com.loopers.batch.job.ranking.domain.InputSummary;
import com.loopers.batch.job.ranking.domain.SnapshotConflictException;
import com.loopers.batch.job.ranking.domain.SnapshotId;
import com.loopers.batch.job.ranking.domain.SnapshotManifest;
import com.loopers.batch.job.ranking.infrastructure.RankingSnapshotStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;

import java.time.Clock;
import java.time.LocalDateTime;

public class FreezeSnapshotTasklet implements Tasklet {
    private static final Logger log = LoggerFactory.getLogger(FreezeSnapshotTasklet.class);

    private final RankingSnapshotStore snapshots;
    private final Clock clock;
    private final SnapshotId snapshotId;
    private final SnapshotId sourceSnapshotId;

    public FreezeSnapshotTasklet(RankingSnapshotStore snapshots, Clock clock, WeeklyRankingParameters parameters) {
        this.snapshots = snapshots;
        this.clock = clock;
        this.snapshotId = parameters.snapshotId();
        this.sourceSnapshotId = parameters.sourceSnapshotId();
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        SnapshotManifest source = sourceSnapshotId == null ? null : loadSource();
        var existing = snapshots.findManifest(snapshotId);
        if (existing.isPresent()) {
            snapshots.verifyFrozenInput(snapshotId, existing.get());
            verifySourceMatches(source, existing.get());
            log.info("Snapshot {} is already frozen; input is reused", snapshotId);
            return RepeatStatus.FINISHED;
        }
        int copied = source == null ? snapshots.copyActivities(snapshotId)
                : snapshots.copyFrozenInput(sourceSnapshotId, snapshotId);
        InputSummary summary = snapshots.summarizeInput(snapshotId);
        if (summary.inputCount() != copied) {
            throw new IllegalStateException("copied " + copied + " rows but summarized " + summary.inputCount());
        }
        LocalDateTime frozenAt = source == null ? LocalDateTime.now(clock) : source.frozenAt();
        var manifest = SnapshotManifest.frozen(snapshotId, frozenAt, summary);
        verifySourceMatches(source, manifest);
        snapshots.insertManifest(manifest);
        contribution.incrementWriteCount(copied);
        log.info("Snapshot {} frozen: inputs={}, products={}, checksum={}",
                snapshotId, summary.inputCount(), summary.productCount(), summary.checksum());
        return RepeatStatus.FINISHED;
    }

    private SnapshotManifest loadSource() {
        var source = snapshots.findManifest(sourceSnapshotId)
                .orElseThrow(() -> new SnapshotConflictException("source snapshot is not frozen: " + sourceSnapshotId));
        if (!source.definedAs(sourceSnapshotId)) {
            throw new SnapshotConflictException("source snapshot manifest conflicts with " + sourceSnapshotId);
        }
        return source;
    }

    private void verifySourceMatches(SnapshotManifest source, SnapshotManifest target) {
        if (source != null && (!target.input().equals(source.input())
                || !target.frozenAt().equals(source.frozenAt()))) {
            throw new SnapshotConflictException("snapshot " + snapshotId + " input differs from " + sourceSnapshotId);
        }
    }
}
