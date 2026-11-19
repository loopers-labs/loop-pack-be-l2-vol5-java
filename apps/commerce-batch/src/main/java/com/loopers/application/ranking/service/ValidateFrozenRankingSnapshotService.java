package com.loopers.application.ranking.service;

import com.loopers.application.ranking.command.FreezeRankingSnapshotCommand;
import com.loopers.domain.ranking.model.SnapshotIntegrityException;
import com.loopers.domain.ranking.policy.FrozenPurchaseInputChecksum;
import com.loopers.domain.ranking.repository.RankingSnapshotRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ValidateFrozenRankingSnapshotService {
    private final RankingSnapshotRepository repository;

    public ValidateFrozenRankingSnapshotService(RankingSnapshotRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public void validate(FreezeRankingSnapshotCommand command) {
        var snapshot = repository.findById(command.snapshotId())
            .orElseThrow(() -> new SnapshotIntegrityException("계산할 고정 snapshot이 없습니다: " + command.snapshotId()));
        snapshot.requireContract(command.snapshotId(), command.period(), command.revision(),
            FrozenPurchaseInputChecksum.CONTRACT_VERSION);
        snapshot.requireFrozen(repository.fingerprint(command.snapshotId(), command.period()));
    }
}
