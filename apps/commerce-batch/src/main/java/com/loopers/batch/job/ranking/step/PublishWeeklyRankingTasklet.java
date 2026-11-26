package com.loopers.batch.job.ranking.step;

import com.loopers.batch.job.ranking.RankingSnapshot;
import com.loopers.batch.job.ranking.RankingSnapshotConflictException;
import com.loopers.batch.job.ranking.RankingSnapshotStore;
import com.loopers.batch.job.ranking.WeeklyRankingJobConfig;
import com.loopers.batch.job.ranking.WeeklySnapshotKey;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * ③ 완성본 공개. step의 트랜잭션 하나 안에서
 * 모든 상품 점수가 있는지 확인 → 계산 순위를 붙여 완성 ranking 저장 → 결과 checksum과 함께 COMPLETED → 기간의 공개 pointer를 조건부로 바꾼다.
 * 중간에 실패하면 ranking·상태·pointer가 모두 rollback되어 고객은 이전 공개본을 그대로 본다.
 * <p>
 * 다시 실행해도 같은 결과로 수렴한다. 이미 COMPLETED면 ranking을 다시 만들지 않고, 이미 이 snapshot이 공개돼 있으면 pointer를 건드리지 않는다
 * (공개 commit 뒤 Batch metadata만 실패한 경우 재시작이 이 경로를 탄다). 더 새 revision이 공개돼 있으면 pointer를 되돌리지 않고 SUPERSEDED로 끝낸다.
 */
@Slf4j
@StepScope
@ConditionalOnProperty(name = "spring.batch.job.name", havingValue = WeeklyRankingJobConfig.JOB_NAME)
@Component
public class PublishWeeklyRankingTasklet implements Tasklet {

    public static final ExitStatus ALREADY_PUBLISHED = new ExitStatus("ALREADY_PUBLISHED");
    public static final ExitStatus SUPERSEDED = new ExitStatus("SUPERSEDED");

    private final RankingSnapshotStore store;
    private final Clock clock;
    private final String periodStart;
    private final String snapshotId;

    public PublishWeeklyRankingTasklet(
        RankingSnapshotStore store,
        Clock clock,
        @Value("#{jobParameters['periodStart']}") String periodStart,
        @Value("#{jobParameters['snapshotId']}") String snapshotId
    ) {
        this.store = store;
        this.clock = clock;
        this.periodStart = periodStart;
        this.snapshotId = snapshotId;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        WeeklySnapshotKey key = WeeklySnapshotKey.of(periodStart, snapshotId);
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);

        RankingSnapshot snapshot = store.findSnapshotForUpdate(key.snapshotId())
            .orElseThrow(() -> new IllegalStateException("고정된 입력이 없는 snapshot은 공개할 수 없습니다. snapshotId=" + key.snapshotId()));
        if (!snapshot.sameWorkAs(key)) {
            throw new RankingSnapshotConflictException("snapshot의 기간·revision이 parameter와 다릅니다. snapshotId=" + key.snapshotId());
        }
        if (snapshot.status() == RankingSnapshot.Status.FROZEN) {
            complete(key, snapshot, now);
        }

        Optional<RankingSnapshotStore.Publication> current = store.findPublicationForUpdate(key.periodStart());
        if (current.isEmpty()) {
            store.insertPublication(key, now);
            log.info("주간 랭킹을 공개했습니다. periodStart={}, snapshotId={}", key.periodStart(), key.snapshotId());
        } else if (current.get().snapshotId().equals(key.snapshotId())) {
            log.info("이미 공개된 snapshot입니다. periodStart={}, snapshotId={}", key.periodStart(), key.snapshotId());
            contribution.setExitStatus(ALREADY_PUBLISHED);
        } else if (store.advancePublication(key, now)) {
            log.info("공개 snapshot을 바꿨습니다. periodStart={}, {} → {}", key.periodStart(), current.get().snapshotId(), key.snapshotId());
        } else {
            log.warn("더 새 revision이 이미 공개돼 있어 pointer를 바꾸지 않습니다. periodStart={}, 공개={}, 이번={}",
                key.periodStart(), current.get().snapshotId(), key.snapshotId());
            contribution.setExitStatus(SUPERSEDED);
        }
        return RepeatStatus.FINISHED;
    }

    private void complete(WeeklySnapshotKey key, RankingSnapshot snapshot, LocalDateTime now) {
        int staged = store.countStage(key.snapshotId());
        if (staged != snapshot.productCount()) {
            throw new IllegalStateException("계산된 상품 수가 고정한 입력의 상품 수와 다릅니다. snapshotId=" + key.snapshotId()
                + ", 계산=" + staged + ", 입력=" + snapshot.productCount());
        }
        int ranked = store.insertRanking(key.snapshotId());
        String resultChecksum = store.resultChecksum(key.snapshotId());
        store.markCompleted(key.snapshotId(), resultChecksum, now);
        log.info("완성 ranking을 만들었습니다. snapshotId={}, 상품 {}개, 결과 checksum={}", key.snapshotId(), ranked, resultChecksum);
    }
}
