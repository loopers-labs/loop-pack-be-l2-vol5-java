package com.loopers.batch.job.ranking;

import java.time.LocalDate;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;

public class WeeklyRankingLauncher {
    private final JobLauncher jobLauncher;
    private final JobExplorer jobExplorer;
    private final Job weeklyRankingJob;

    public WeeklyRankingLauncher(JobLauncher jobLauncher, JobExplorer jobExplorer, Job weeklyRankingJob) {
        this.jobLauncher = jobLauncher;
        this.jobExplorer = jobExplorer;
        this.weeklyRankingJob = weeklyRankingJob;
    }

    public JobExecution launch(LocalDate periodStart, Integer rankingSize) throws Exception {
        return null;
    }
}
