package com.loopers.application.ranking;

import com.loopers.domain.ranking.CompletedRanking;
import com.loopers.domain.ranking.RankingPublicationStore;
import com.loopers.domain.ranking.RankingSnapshotConflictException;
import com.loopers.domain.ranking.RankingSnapshotStore;
import com.loopers.domain.ranking.WeeklyRankingKey;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@Service
public class WeeklyRankingPublicationService {
    private final RankingSnapshotStore snapshots;
    private final RankingPublicationStore publications;

    public WeeklyRankingPublicationService(RankingSnapshotStore snapshots, RankingPublicationStore publications) {
        this.snapshots = Objects.requireNonNull(snapshots);
        this.publications = Objects.requireNonNull(publications);
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public void publish(WeeklyRankingKey key) {
        Objects.requireNonNull(key, "Publication requires a weekly snapshot identity");
        // The stage writer locks this same manifest before writing; publication cannot race a final score update.
        var state = publications.lockSnapshot(key);
        var input = snapshots.find(key)
            .orElseThrow(() -> new RankingSnapshotConflictException("Publication requires existing frozen input"));
        var ranking = CompletedRanking.calculate(input, publications.readScores(key.snapshotId()));
        var existing = publications.readResults(key.snapshotId(), "COMPLETE".equals(state.calculationState()));
        if ("CALCULATING".equals(state.calculationState())) {
            if (!existing.isEmpty() || state.resultCount() != null || state.checksumVersion() != null || state.checksum() != null) {
                throw new RankingSnapshotConflictException("Uncompleted snapshot already contains result facts");
            }
            publications.insertResults(ranking);
            publications.complete(ranking);
        } else if ("COMPLETE".equals(state.calculationState())) {
            if (!ranking.products().equals(existing) || state.resultCount() == null
                || state.resultCount() != ranking.products().size()
                || !ranking.checksumVersion().equals(state.checksumVersion()) || !ranking.checksum().equals(state.checksum())) {
                throw new RankingSnapshotConflictException("Completed ranking differs from its frozen facts or result manifest");
            }
        } else {
            throw new RankingSnapshotConflictException("Unknown ranking calculation state");
        }
        // Result rows, completion metadata and this pointer change commit or roll back as one local DB transaction.
        publications.advancePointer(key);
    }
}
