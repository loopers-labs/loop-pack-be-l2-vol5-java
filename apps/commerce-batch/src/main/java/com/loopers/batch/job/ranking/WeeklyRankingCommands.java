package com.loopers.batch.job.ranking;

import com.loopers.batch.job.ranking.WeeklyRankingSnapshotRepository.SnapshotManifest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * 운영 명령. 사람은 기간만 넘기고 revision 은 여기서 정한다 (9주차 D-184).
 * 늦은 구매 확인은 결과를 바꾸지 않고(D-191), 포기는 manifest 상태로만 표시한다(D-185)
 */
// job 이 이 이름일 때만 Job bean 이 하나 있다
@ConditionalOnProperty(name = "spring.batch.job.name", havingValue = WeeklyRankingJobConfig.JOB_NAME)
@Slf4j
@RequiredArgsConstructor
@Component
public class WeeklyRankingCommands {

    private final JobLauncher jobLauncher;
    private final Job weeklyRankingJob;
    private final WeeklyRankingSnapshotRepository snapshots;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    /** 최대 revision 이 없으면 r1, 공개됐거나 포기됐으면 다음 번호, 그 밖(FROZEN · COMPLETED)이면 같은 번호로 재시작 */
    public WeeklyRankingParameters nextParameters(LocalDate periodStart) {
        Optional<SnapshotManifest> latest = snapshots.findLatest(periodStart);
        if (latest.isEmpty()) {
            return WeeklyRankingParameters.of(periodStart, 1);
        }
        SnapshotManifest manifest = latest.get();
        if (manifest.is("PUBLISHED") || manifest.is("ABANDONED")) {
            return WeeklyRankingParameters.of(periodStart, manifest.revision() + 1);
        }
        return WeeklyRankingParameters.of(periodStart, manifest.revision());
    }

    public JobExecution run(LocalDate periodStart) throws Exception {
        WeeklyRankingParameters params = nextParameters(periodStart);
        log.info("주간 랭킹 실행. snapshotId={}", params.snapshotId());
        JobExecution execution = jobLauncher.run(weeklyRankingJob, params.toJobParameters());
        log.info("주간 랭킹 실행 결과. snapshotId={} status={}", params.snapshotId(), execution.getStatus());
        if (execution.getStatus() != BatchStatus.COMPLETED) {
            throw new IllegalStateException("주간 랭킹 실행 실패. snapshotId=" + params.snapshotId() + " status=" + execution.getStatus());
        }
        return execution;
    }

    /** 공개본이 없으면 -1. 비교 기준은 공개 snapshot 의 입력이다 */
    public int countLateArrivals(LocalDate periodStart) {
        Optional<Integer> publishedRevision = snapshots.findPublishedRevision(periodStart);
        if (publishedRevision.isEmpty()) {
            log.info("늦은 구매 확인: 공개본 없음. periodStart={}", periodStart);
            return -1;
        }
        String snapshotId = WeeklyRankingParameters.snapshotIdOf(periodStart, publishedRevision.get());
        int count = snapshots.countMissingFromSnapshot(snapshotId, periodStart, periodStart.plusDays(7));
        log.info("늦은 구매 확인. snapshotId={} lateArrivals={}", snapshotId, count);
        return count;
    }

    public void abandon(LocalDate periodStart, int revision) {
        String snapshotId = WeeklyRankingParameters.of(periodStart, revision).snapshotId();
        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
        Integer updated = transactionTemplate.execute(status -> snapshots.abandon(snapshotId, now));
        if (updated == null || updated != 1) {
            throw new IllegalStateException("포기할 수 없음(없거나 공개본이거나 이미 포기됨). snapshotId=" + snapshotId);
        }
        log.info("revision 포기. snapshotId={}", snapshotId);
    }
}
