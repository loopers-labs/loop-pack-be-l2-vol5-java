package com.loopers.batch.job.ranking;

import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

public class WeeklyRankingRecovery {
    private final JobExplorer explorer;
    private final JobRepository repository;
    private final TransactionTemplate transactions;

    public WeeklyRankingRecovery(JobExplorer explorer, JobRepository repository,
            PlatformTransactionManager transactionManager) {
        this.explorer = explorer;
        this.repository = repository;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /** 호출자가 해당 프로세스의 종료를 외부에서 확인한 뒤에만 호출한다. */
    public void abandonAfterTerminationConfirmed(long executionId) {
        transactions.executeWithoutResult(status -> {
            JobExecution execution = explorer.getJobExecution(executionId);
            if (execution == null || !WeeklyRankingJobConfig.JOB_NAME.equals(execution.getJobInstance().getJobName())) {
                throw new IllegalArgumentException("weeklyRanking execution not found: " + executionId);
            }
            if (!isActive(execution.getStatus())) {
                throw new IllegalStateException("execution is not active: " + execution.getStatus());
            }

            LocalDateTime endedAt = LocalDateTime.now();
            for (StepExecution step : execution.getStepExecutions()) {
                if (isActive(step.getStatus())) {
                    step.setStatus(BatchStatus.ABANDONED);
                    step.setExitStatus(new ExitStatus("ABANDONED", "process termination confirmed by operator"));
                    step.setEndTime(endedAt);
                    repository.update(step);
                }
            }
            execution.setStatus(BatchStatus.ABANDONED);
            execution.setExitStatus(new ExitStatus("ABANDONED", "process termination confirmed by operator"));
            execution.setEndTime(endedAt);
            repository.update(execution);
        });
    }

    private static boolean isActive(BatchStatus status) {
        return status == BatchStatus.STARTING || status == BatchStatus.STARTED || status == BatchStatus.STOPPING;
    }
}
