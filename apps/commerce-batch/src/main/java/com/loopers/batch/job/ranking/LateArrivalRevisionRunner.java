package com.loopers.batch.job.ranking;

import com.loopers.batch.job.ranking.LateArrivalDetector.Action;
import com.loopers.batch.job.ranking.LateArrivalDetector.Decision;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.batch.JobExecutionEvent;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;

/**
 * 늦은 구매 감지 실행기. 바깥 스케줄러가 매시 이 모드로 commerce-batch를 실행한다.
 * <pre>
 * --spring.batch.job.name=weeklyRanking --spring.batch.job.enabled=false --commerce.ranking.late-check.enabled=true
 * </pre>
 * 판단(LateArrivalDetector)대로 같은 프로세스에서 weeklyRanking을 실행한다. Batch job 안에서 다른 job을 띄우지 않으려고
 * job이 아니라 ApplicationRunner로 둔다. 실행 결과를 JobExecutionEvent로 알려 프로세스 종료 코드에 반영한다.
 */
@Slf4j
public class LateArrivalRevisionRunner implements ApplicationRunner {
    private final LateArrivalDetector detector;
    private final JobLauncher jobLauncher;
    private final Job weeklyRankingJob;
    private final ApplicationEventPublisher events;

    public LateArrivalRevisionRunner(LateArrivalDetector detector, JobLauncher jobLauncher, Job weeklyRankingJob,
                                     ApplicationEventPublisher events) {
        this.detector = detector;
        this.jobLauncher = jobLauncher;
        this.weeklyRankingJob = weeklyRankingJob;
        this.events = events;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        runOnce();
    }

    /** 한 번 판단하고, 필요하면 job을 실행해 그 결과를 돌려준다. 할 일이 없으면 비어 있다. */
    public Optional<JobExecution> runOnce() throws Exception {
        Decision decision = detector.decide();
        log.info("늦은 구매 감지: {} {} {} — {}", decision.periodStart(), decision.action(),
            decision.snapshotId() == null ? "" : decision.snapshotId(), decision.reason());
        if (decision.action() == Action.NONE) {
            return Optional.empty();
        }
        JobExecution execution = jobLauncher.run(weeklyRankingJob, new JobParametersBuilder()
            .addString(WeeklyRankingParametersValidator.PERIOD_START, decision.periodStart().toString(), true)
            .addString(WeeklyRankingParametersValidator.SNAPSHOT_ID, decision.snapshotId(), true)
            .toJobParameters());
        events.publishEvent(new JobExecutionEvent(execution));
        return Optional.of(execution);
    }
}
