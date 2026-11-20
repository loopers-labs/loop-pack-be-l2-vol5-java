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
 * ① 입력 고정. 그 주의 구매 활동을 snapshot 입력으로 복사하고 건수·상품 수·checksum과 함께 FROZEN으로 기록한다.
 * step의 트랜잭션(실제 DB 트랜잭션 매니저) 하나 안에서 실행되므로, 중간에 실패하면 snapshot 정보와 입력이 함께 rollback되어 반쪽 snapshot이 남지 않는다.
 * 이미 같은 일로 고정된 snapshot이면 아무것도 하지 않는다(공개 후 metadata만 실패한 재실행 등). 다른 일이면 덮어쓰지 않고 충돌로 실패한다.
 */
@Slf4j
@StepScope
@ConditionalOnProperty(name = "spring.batch.job.name", havingValue = WeeklyRankingJobConfig.JOB_NAME)
@Component
public class FreezeWeeklyInputTasklet implements Tasklet {

    private final RankingSnapshotStore store;
    private final Clock clock;
    private final String periodStart;
    private final String snapshotId;

    public FreezeWeeklyInputTasklet(
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

        Optional<RankingSnapshot> existing = store.findSnapshotForUpdate(key.snapshotId());
        if (existing.isPresent()) {
            if (!existing.get().sameWorkAs(key)) {
                throw new RankingSnapshotConflictException(
                    "같은 snapshotId에 다른 기간·revision이 이미 있습니다. snapshotId=" + key.snapshotId()
                        + ", 기존=" + existing.get().periodStart() + "/r" + existing.get().revision());
            }
            log.info("이미 고정된 입력을 그대로 사용합니다. snapshotId={}, status={}", key.snapshotId(), existing.get().status());
            contribution.setExitStatus(new ExitStatus("ALREADY_FROZEN"));
            return RepeatStatus.FINISHED;
        }

        int maxRevision = store.maxRevision(key.periodStart());
        if (key.revision() <= maxRevision) {
            throw new RankingSnapshotConflictException(
                "새 snapshot의 revision은 기존 최대값보다 커야 합니다. snapshotId=" + key.snapshotId() + ", 기존 최대 revision=" + maxRevision);
        }

        LocalDateTime frozenAt = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        int inputCount = store.copyActivities(key);
        int invalid = store.countNonPositiveQuantity(key.snapshotId());
        if (invalid > 0) {
            // 검증된 구매 사실이어야 하므로 skip하지 않고 실패를 드러낸다. 같은 트랜잭션이라 복사한 입력도 rollback된다.
            throw new IllegalStateException("수량이 0 이하인 구매 활동이 있습니다. snapshotId=" + key.snapshotId() + ", 건수=" + invalid);
        }
        int productCount = store.countProducts(key.snapshotId());
        String checksum = store.inputChecksum(key.snapshotId());
        store.insertFrozenSnapshot(key, frozenAt, inputCount, productCount, checksum);

        log.info("입력을 고정했습니다. snapshotId={}, 활동 {}행, 상품 {}개, checksum={}", key.snapshotId(), inputCount, productCount, checksum);
        return RepeatStatus.FINISHED;
    }
}
