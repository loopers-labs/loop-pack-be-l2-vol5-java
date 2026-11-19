package com.loopers.application.ranking.service;

import com.loopers.application.ranking.command.PublishWeeklyRankingCommand;
import com.loopers.application.ranking.result.WeeklyRankingPublicationResult;
import com.loopers.domain.ranking.model.SnapshotConflictException;
import com.loopers.domain.ranking.model.SnapshotIntegrityException;
import com.loopers.domain.ranking.model.vo.InputFingerprint;
import com.loopers.domain.ranking.repository.RankingSnapshotRepository;
import com.loopers.domain.ranking.repository.WeeklyRankingPublicationRepository;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PublishWeeklyRankingService {
    private final RankingSnapshotRepository snapshots;
    private final WeeklyRankingPublicationRepository publications;

    public PublishWeeklyRankingService(RankingSnapshotRepository snapshots, WeeklyRankingPublicationRepository publications) {
        this.snapshots = snapshots;
        this.publications = publications;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public WeeklyRankingPublicationResult publish(PublishWeeklyRankingCommand command) {
        return publishChecked(command, InputFingerprint.CONTRACT_VERSION, null);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public WeeklyRankingPublicationResult publishPartitioned(PublishWeeklyRankingCommand command, InputFingerprint verified) {
        return publishChecked(command, 2, Objects.requireNonNull(verified));
    }

    private WeeklyRankingPublicationResult publishChecked(PublishWeeklyRankingCommand command, int contract,
                                                          InputFingerprint verified) {
        Objects.requireNonNull(command, "command");
        var snapshot = snapshots.findById(command.snapshotId())
            .orElseThrow(() -> new SnapshotIntegrityException("공개할 고정 snapshot이 없습니다: " + command.snapshotId()));
        snapshot.requireContract(command.snapshotId(), command.period(), command.revision(), contract);
        snapshot.requireFrozen(snapshot.inputs());
        var current = publications.lockPointer(command.period());
        if (current.isPresent() && current.get().revision() > command.revision()) {
            return WeeklyRankingPublicationResult.SUPERSEDED;
        }
        if (current.isPresent() && current.get().revision() == command.revision()
            && !current.get().snapshotId().equals(command.snapshotId())) {
            throw new SnapshotConflictException("같은 기간·revision의 공개 snapshotId가 다릅니다.");
        }
        // Validate in this publication transaction, not via the REQUIRES_NEW calculation validator.
        snapshot.requireFrozen(verified != null ? verified : snapshots.fingerprint(command.snapshotId(), command.period()));
        var completed = publications.findCompletion(command.snapshotId());
        if (completed.isEmpty()) {
            publications.materialize(command.snapshotId());
        }
        var result = publications.inspectRankings(command.snapshotId()).requireComplete();
        if (completed.isPresent()) {
            completed.get().requireResult(result);
            if (current.isPresent() && current.get().snapshotId().equals(command.snapshotId())) {
                return WeeklyRankingPublicationResult.ALREADY_PUBLISHED;
            }
        } else {
            publications.complete(command.snapshotId(), result).requireResult(result);
        }
        publications.advancePointer(snapshot);
        return WeeklyRankingPublicationResult.PUBLISHED;
    }
}
