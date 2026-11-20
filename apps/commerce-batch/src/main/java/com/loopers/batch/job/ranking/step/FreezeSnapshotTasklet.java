package com.loopers.batch.job.ranking.step;

import com.loopers.batch.job.ranking.domain.InputSummary;
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

    public FreezeSnapshotTasklet(RankingSnapshotStore snapshots, Clock clock, SnapshotId snapshotId) {
        this.snapshots = snapshots;
        this.clock = clock;
        this.snapshotId = snapshotId;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        var existing = snapshots.findManifest(snapshotId);
        if (existing.isPresent()) {
            snapshots.verifyFrozenInput(snapshotId, existing.get());
            log.info("Snapshot {} is already frozen; input is reused", snapshotId);
            return RepeatStatus.FINISHED;
        }
        int copied = snapshots.copyActivities(snapshotId);
        InputSummary summary = snapshots.summarizeInput(snapshotId);
        if (summary.inputCount() != copied) {
            throw new IllegalStateException("copied " + copied + " rows but summarized " + summary.inputCount());
        }
        snapshots.insertManifest(SnapshotManifest.frozen(snapshotId, LocalDateTime.now(clock), summary));
        contribution.incrementWriteCount(copied);
        log.info("Snapshot {} frozen: inputs={}, products={}, checksum={}",
                snapshotId, summary.inputCount(), summary.productCount(), summary.checksum());
        return RepeatStatus.FINISHED;
    }

}
