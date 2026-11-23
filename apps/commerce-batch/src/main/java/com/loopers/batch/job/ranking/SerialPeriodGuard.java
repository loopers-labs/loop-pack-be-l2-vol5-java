package com.loopers.batch.job.ranking;

import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.explore.JobExplorer;

import java.util.List;

public class SerialPeriodGuard implements JobExecutionListener {
    private final JobExplorer jobExplorer;

    public SerialPeriodGuard(JobExplorer jobExplorer) {
        this.jobExplorer = jobExplorer;
    }

    @Override
    public void beforeJob(JobExecution execution) {
        String periodStart = execution.getJobParameters().getString(WeeklyRankingParameters.PERIOD_START);
        List<Long> others = jobExplorer.findRunningJobExecutions(execution.getJobInstance().getJobName()).stream()
                .filter(running -> !running.getId().equals(execution.getId()))
                .filter(running -> periodStart.equals(
                        running.getJobParameters().getString(WeeklyRankingParameters.PERIOD_START)))
                .map(JobExecution::getId)
                .toList();
        if (!others.isEmpty()) {
            throw new IllegalStateException(
                    "weeklyRanking for periodStart " + periodStart + " is already running: executions " + others);
        }
    }
}
