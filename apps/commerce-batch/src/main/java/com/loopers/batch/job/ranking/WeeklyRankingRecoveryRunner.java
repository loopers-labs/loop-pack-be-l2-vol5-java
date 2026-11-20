package com.loopers.batch.job.ranking;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

public class WeeklyRankingRecoveryRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(WeeklyRankingRecoveryRunner.class);

    private final WeeklyRankingRecovery recovery;
    private final long executionId;

    public WeeklyRankingRecoveryRunner(WeeklyRankingRecovery recovery, long executionId,
            boolean terminationConfirmed, boolean automaticJobEnabled) {
        if (!terminationConfirmed) {
            throw new IllegalArgumentException("confirm process termination before recovering a job");
        }
        if (automaticJobEnabled) {
            throw new IllegalArgumentException("set spring.batch.job.enabled=false for recovery");
        }
        this.recovery = recovery;
        this.executionId = executionId;
    }

    @Override
    public void run(ApplicationArguments args) {
        recovery.abandonAfterTerminationConfirmed(executionId);
        log.info("weeklyRanking execution {} abandoned after operator confirmed termination", executionId);
    }
}
