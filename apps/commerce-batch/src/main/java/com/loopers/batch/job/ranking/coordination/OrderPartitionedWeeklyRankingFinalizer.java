package com.loopers.batch.job.ranking.coordination;

import com.loopers.application.ranking.command.PublishWeeklyRankingCommand;
import com.loopers.application.ranking.result.WeeklyRankingPublicationResult;
import com.loopers.application.ranking.service.PublishWeeklyRankingService;
import com.loopers.domain.ranking.model.SnapshotIntegrityException;
import com.loopers.infrastructure.persistence.ranking.JdbcOrderPartitionedRankingReduction;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class OrderPartitionedWeeklyRankingFinalizer implements WeeklyPlanFinalizer {
    private final JdbcOrderPartitionedRankingReduction reduction;
    private final PublishWeeklyRankingService publication;
    private final TransactionTemplate transaction;

    public OrderPartitionedWeeklyRankingFinalizer(JdbcOrderPartitionedRankingReduction reduction, PublishWeeklyRankingService publication,
                                            PlatformTransactionManager manager) {
        this.reduction = reduction;
        this.publication = publication;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Override
    public void finalizePlan(UUID snapshotId) {
        try {
            transaction.executeWithoutResult(status -> publishIfComplete(snapshotId));
        } catch (SnapshotIntegrityException mismatch) {
            // The failed reduction and pointer change rolled back. Quarantine only this open revision.
            transaction.executeWithoutResult(status -> reduction.finish(snapshotId, "CONFLICT"));
            throw mismatch;
        }
    }

    private void publishIfComplete(UUID id) {
        var plan = reduction.lockPlan(id);
        if (!"OPEN".equals(plan.state())) {
            return;
        }
        reduction.verifyAndSeal(id, plan).ifPresent(fingerprint -> {
            reduction.reduce(id);
            var command = new PublishWeeklyRankingCommand(id, plan.periodStart(), plan.revision());
            var outcome = publication.publishPartitioned(command, fingerprint);
            reduction.finish(id, outcome == WeeklyRankingPublicationResult.SUPERSEDED ? "SUPERSEDED" : "PUBLISHED");
        });
    }
}
