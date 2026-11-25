package com.loopers.batch.job.weeklyranking;

import com.loopers.batch.domain.InputSummary;
import com.loopers.batch.domain.SnapshotId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * 입력 고정 step (W9 D-3). step이 연 TX 하나(READ COMMITTED) 안에서 아래를 한 번에 한다.
 * ① 실제 격리 수준을 실행 기록에 남김
 * ② snapshot이 이미 있으면 저장된 입력을 다시 요약해 비교 — 같으면 복사 없이 통과, 다르면 실패
 * ③ 없으면 revision 규칙 확인 (그 주의 가장 큰 revision + 1)
 * ④ 그 주 구매를 한 문장으로 복사 → ⑤ 복사한 입력을 요약 → ⑥ snapshot 저장 (FROZEN)
 * 도중에 실패하면 복사까지 모두 rollback되어 아무것도 남지 않는다.
 */
@Slf4j
@StepScope
@ConditionalOnProperty(name = "spring.batch.job.name", havingValue = WeeklyRankingJobConfig.JOB_NAME)
@RequiredArgsConstructor
@Component
public class InputFreezeTasklet implements Tasklet {

    public static final String ISOLATION_KEY = "transactionIsolation";

    private final RankingSnapshotJdbcRepository repository;
    private final Clock clock;

    @Value("#{jobParameters['periodStart']}")
    private LocalDate periodStart;

    @Value("#{jobParameters['revision']}")
    private Long revision;

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        contribution.getStepExecution().getExecutionContext().putString(ISOLATION_KEY, repository.currentIsolation());

        String snapshotId = SnapshotId.of(periodStart, revision).value();
        Optional<InputSummary> frozen = repository.findFrozenSummary(snapshotId);
        if (frozen.isPresent()) {
            verifySameInput(snapshotId, frozen.get(), repository.summarizeInput(snapshotId));
            log.info("[입력 고정] 이미 고정된 snapshot과 같아 복사 없이 통과합니다. [snapshotId = {}]", snapshotId);
            return RepeatStatus.FINISHED;
        }

        long maxRevision = repository.findMaxRevision(periodStart);
        if (revision != maxRevision + 1) {
            throw new IllegalStateException("새 revision은 그 주의 가장 큰 revision + 1이어야 합니다. [periodStart = " + periodStart
                + ", 가장 큰 revision = " + maxRevision + ", 요청 revision = " + revision + "]");
        }

        LocalDateTime nowUtc = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        int copied = repository.copyInput(snapshotId, periodStart.atStartOfDay(), periodStart.plusWeeks(1).atStartOfDay(), nowUtc);
        InputSummary summary = repository.summarizeInput(snapshotId);
        repository.insertFrozenSnapshot(snapshotId, periodStart, revision, summary, nowUtc);
        log.info("[입력 고정] snapshot을 고정했습니다. [snapshotId = {}, 복사 = {}행, 건수 = {}, 수량 합 = {}, checksum = {}]",
            snapshotId, copied, summary.count(), summary.quantitySum(), summary.checksum());
        return RepeatStatus.FINISHED;
    }

    private static void verifySameInput(String snapshotId, InputSummary stored, InputSummary recomputed) {
        if (!stored.equals(recomputed)) {
            throw new IllegalStateException("고정 입력이 달라졌습니다. [snapshotId = " + snapshotId + "] 저장: " + describe(stored) + " / 다시 계산: " + describe(recomputed));
        }
    }

    private static String describe(InputSummary summary) {
        return summary.count() + "건·수량 " + summary.quantitySum() + "·" + summary.checksum().substring(0, 8);
    }
}
