package com.loopers.batch.job.ranking;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;

// 런처(docs/week9/commerce-ranking.md 9절). 그 주에 마지막 실행이 COMPLETED가 아닌 JobInstance가 있으면 그 parameter 그대로 다시
// 실행한다(번호를 새로 만들지 않는다). 다만 pointer revision이 그 실행 revision 이상이면 건너뛴다(w9d): 공개는 마지막 Step이고 한 TX라
// pointer가 r 이상이면 r의 랭킹은 이미 다 들어갔거나 더 새것이 공개됐다. "끝났나"의 기준은 pointer이고 Batch 기록은 고치지 않는다.
// 재시작할 것이 없으면 그 주의 최대 revision(건너뛴 실행 포함) + 1로 snapshotId를 만든다. 공개 전 단계에서 아직 실행 중인 같은 주를
// 다시 부르면 같은 JobInstance라 Spring Batch가 거절한다(JobExecutionAlreadyRunningException). 이력은 Batch metadata에서 찾는다:
// Step 1에서 실패해 snapshot 정보 행이 없는 실행도 찾아야 하기 때문이다.
public class WeeklyRankingLauncher {
    private static final int PAGE = 500;

    private final JobLauncher jobLauncher;
    private final JobExplorer jobExplorer;
    private final Job weeklyRankingJob;
    private final WeeklyRankingRepository weeklyRankingRepository;

    public WeeklyRankingLauncher(JobLauncher jobLauncher, JobExplorer jobExplorer, Job weeklyRankingJob,
        WeeklyRankingRepository weeklyRankingRepository) {
        this.jobLauncher = jobLauncher;
        this.jobExplorer = jobExplorer;
        this.weeklyRankingJob = weeklyRankingJob;
        this.weeklyRankingRepository = weeklyRankingRepository;
    }

    public JobExecution launch(LocalDate periodStart, Integer rankingSize) throws Exception {
        List<JobExecution> latestOfPeriod = latestExecutionsOf(periodStart);
        int publishedRevision = weeklyRankingRepository.publishedRevision(periodStart).orElse(0);
        Optional<JobExecution> unfinished = latestOfPeriod.stream()
            .filter(execution -> execution.getStatus() != BatchStatus.COMPLETED)
            .filter(execution -> revisionOf(execution) > publishedRevision)
            .max(Comparator.comparingInt(WeeklyRankingLauncher::revisionOf));
        if (unfinished.isPresent()) {
            return jobLauncher.run(weeklyRankingJob, unfinished.get().getJobParameters());
        }
        int nextRevision = latestOfPeriod.stream().mapToInt(WeeklyRankingLauncher::revisionOf).max().orElse(0) + 1;
        JobParametersBuilder parameters = new JobParametersBuilder()
            .addString(WeeklyRankingParameters.PERIOD_START, periodStart.toString())
            .addString(WeeklyRankingParameters.SNAPSHOT_ID, WeeklyRankingParameters.snapshotIdOf(periodStart, nextRevision));
        if (rankingSize != null) {
            parameters.addLong(WeeklyRankingParameters.RANKING_SIZE, rankingSize.longValue());
        }
        return jobLauncher.run(weeklyRankingJob, parameters.toJobParameters());
    }

    // 그 주(periodStart parameter)의 JobInstance마다 마지막 실행.
    private List<JobExecution> latestExecutionsOf(LocalDate periodStart) {
        List<JobExecution> latest = new ArrayList<JobExecution>();
        List<JobInstance> page = jobExplorer.getJobInstances(weeklyRankingJob.getName(), 0, PAGE);
        for (int start = PAGE; !page.isEmpty(); start += PAGE) {
            for (JobInstance instance : page) {
                JobExecution execution = jobExplorer.getLastJobExecution(instance);
                if (execution != null && periodStart.toString().equals(periodOf(execution.getJobParameters()))) {
                    latest.add(execution);
                }
            }
            page = jobExplorer.getJobInstances(weeklyRankingJob.getName(), start, PAGE);
        }
        return latest;
    }

    private static String periodOf(JobParameters parameters) {
        return parameters.getString(WeeklyRankingParameters.PERIOD_START);
    }

    private static int revisionOf(JobExecution execution) {
        JobParameters parameters = execution.getJobParameters();
        return WeeklyRankingParameters.parse(periodOf(parameters), parameters.getString(WeeklyRankingParameters.SNAPSHOT_ID))
            .revision();
    }
}
