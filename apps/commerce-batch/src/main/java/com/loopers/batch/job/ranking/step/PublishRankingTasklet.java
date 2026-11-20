package com.loopers.batch.job.ranking.step;

import com.loopers.batch.job.ranking.domain.PublishedSnapshot;
import com.loopers.batch.job.ranking.domain.SnapshotId;
import com.loopers.batch.job.ranking.domain.SnapshotManifest;
import com.loopers.batch.job.ranking.domain.SnapshotStatus;
import com.loopers.batch.job.ranking.infrastructure.RankingPublicationStore;
import com.loopers.batch.job.ranking.infrastructure.RankingSnapshotStore;
import com.loopers.batch.job.ranking.infrastructure.ScoreStageStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

public class PublishRankingTasklet implements Tasklet {
    private static final Logger log = LoggerFactory.getLogger(PublishRankingTasklet.class);

    private final RankingSnapshotStore snapshots;
    private final ScoreStageStore stages;
    private final RankingPublicationStore publications;
    private final Clock clock;
    private final SnapshotId snapshotId;

    public PublishRankingTasklet(RankingSnapshotStore snapshots, ScoreStageStore stages,
            RankingPublicationStore publications, Clock clock, SnapshotId snapshotId) {
        this.snapshots = snapshots;
        this.stages = stages;
        this.publications = publications;
        this.clock = clock;
        this.snapshotId = snapshotId;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        SnapshotManifest manifest = snapshots.lockManifest(snapshotId)
                .orElseThrow(() -> new IllegalStateException("snapshot " + snapshotId + " is not frozen"));
        if (manifest.status() != SnapshotStatus.FROZEN) {
            log.info("Snapshot {} already completed as {}; nothing to publish", snapshotId, manifest.status());
            return RepeatStatus.FINISHED;
        }
        if (clock.instant().isBefore(snapshotId.period().end().atStartOfDay(ZoneOffset.UTC).toInstant())) {
            throw new IllegalStateException("weekly ranking period has not ended: " + snapshotId.period().start());
        }
        snapshots.verifyFrozenInput(snapshotId, manifest);
        verifyScores(manifest);
        int ranked = publications.insertRanking(snapshotId);
        if (ranked != manifest.input().productCount()) {
            throw new IllegalStateException("ranked " + ranked + " of " + manifest.input().productCount());
        }
        String resultChecksum = publications.resultChecksum(snapshotId);
        LocalDateTime now = LocalDateTime.now(clock);

        if (publications.publishIfNewer(snapshotId, now)) {
            snapshots.markCompleted(snapshotId, SnapshotStatus.PUBLISHED, resultChecksum, now);
            log.info("Snapshot {} published: products={}, checksum={}", snapshotId, ranked, resultChecksum);
            return RepeatStatus.FINISHED;
        }
        snapshots.markCompleted(snapshotId, SnapshotStatus.SKIPPED_STALE, resultChecksum, now);
        String published = publications.findPublication(snapshotId.period())
                .map(PublishedSnapshot::snapshotId).orElse("unknown");
        contribution.setExitStatus(ExitStatus.COMPLETED.addExitDescription(
                "SKIPPED_STALE: newer snapshot " + published + " is published"));
        log.warn("Snapshot {} completed but not published; newer snapshot {} is public", snapshotId, published);
        return RepeatStatus.FINISHED;
    }

    private void verifyScores(SnapshotManifest manifest) {
        int scores = stages.countScores(snapshotId);
        if (scores != manifest.input().productCount()) {
            throw new IllegalStateException("snapshot " + snapshotId + " has " + scores + " scores for "
                    + manifest.input().productCount() + " products");
        }
        int mismatches = stages.countMismatches(snapshotId);
        if (mismatches != 0) {
            throw new IllegalStateException("snapshot " + snapshotId + " has " + mismatches + " mismatched scores");
        }
    }
}
