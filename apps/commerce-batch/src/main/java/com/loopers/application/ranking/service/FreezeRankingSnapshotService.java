package com.loopers.application.ranking.service;

import com.loopers.application.ranking.command.FreezeRankingSnapshotCommand;
import com.loopers.application.ranking.result.FrozenRankingSnapshotResult;
import com.loopers.domain.ranking.repository.RankingSnapshotRepository;
import com.loopers.domain.ranking.model.RankingPeriod;
import com.loopers.domain.ranking.model.RankingSnapshot;
import com.loopers.domain.ranking.model.SnapshotConflictException;
import com.loopers.domain.ranking.policy.FrozenPurchaseInputChecksum;
import com.loopers.domain.ranking.repository.SnapshotReservationConflictException;
import com.loopers.application.ranking.support.SnapshotFreezeRetryableException;
import java.util.Objects;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class FreezeRankingSnapshotService {
    private final RankingSnapshotRepository repository;
    private final TransactionTemplate freezeTransaction;
    private final TransactionTemplate reuseTransaction;

    public FreezeRankingSnapshotService(RankingSnapshotRepository repository, PlatformTransactionManager transactionManager) {
        this.repository = repository;
        freezeTransaction = newTransaction(transactionManager, false);
        reuseTransaction = newTransaction(transactionManager, true);
    }

    public FrozenRankingSnapshotResult freeze(FreezeRankingSnapshotCommand command) {
        Objects.requireNonNull(command, "command");
        var period = command.period();
        try {
            try {
                return Objects.requireNonNull(freezeTransaction.execute(status -> freezeOrReuse(command, period)));
            } catch (SnapshotReservationConflictException duplicate) {
                // execute has completed rollback before this new transaction starts.
                return Objects.requireNonNull(reuseTransaction.execute(status -> requireReusable(command, period)));
            }
        } catch (TransientDataAccessException exception) {
            throw new SnapshotFreezeRetryableException(exception);
        }
    }

    private FrozenRankingSnapshotResult freezeOrReuse(FreezeRankingSnapshotCommand command, RankingPeriod period) {
        var existing = repository.findById(command.snapshotId());
        if (existing.isPresent()) {
            return verifyAndResult(existing.get(), command, period);
        }
        repository.reserve(command.snapshotId(), period, command.revision());
        repository.copyInputs(command.snapshotId(), period);
        var inputs = repository.fingerprint(command.snapshotId(), period);
        var completed = repository.complete(command.snapshotId(), inputs);
        completed.requireContract(command.snapshotId(), period, command.revision(), FrozenPurchaseInputChecksum.CONTRACT_VERSION);
        completed.requireFrozen(inputs);
        return result(completed);
    }

    private FrozenRankingSnapshotResult requireReusable(FreezeRankingSnapshotCommand command, RankingPeriod period) {
        var existing = repository.findById(command.snapshotId()).orElseThrow(() -> new SnapshotConflictException(
            "다른 snapshotId가 같은 기간·revision을 사용하고 있습니다."));
        return verifyAndResult(existing, command, period);
    }

    private FrozenRankingSnapshotResult verifyAndResult(RankingSnapshot snapshot, FreezeRankingSnapshotCommand command,
                                                       RankingPeriod period) {
        snapshot.requireContract(command.snapshotId(), period, command.revision(), FrozenPurchaseInputChecksum.CONTRACT_VERSION);
        var actual = repository.fingerprint(snapshot.snapshotId(), snapshot.period());
        snapshot.requireFrozen(actual);
        return result(snapshot);
    }

    private FrozenRankingSnapshotResult result(RankingSnapshot snapshot) {
        return new FrozenRankingSnapshotResult(snapshot.snapshotId(), snapshot.period().start(), snapshot.period().end(),
            snapshot.revision(), snapshot.frozenAt(), snapshot.inputs().count(), snapshot.inputs().sha256());
    }

    private TransactionTemplate newTransaction(PlatformTransactionManager manager, boolean readOnly) {
        var transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setReadOnly(readOnly);
        return transaction;
    }
}
