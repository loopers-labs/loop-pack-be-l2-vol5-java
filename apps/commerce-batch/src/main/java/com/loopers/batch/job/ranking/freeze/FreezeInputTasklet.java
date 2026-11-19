package com.loopers.batch.job.ranking.freeze;

import com.loopers.batch.job.ranking.WeeklyRankingParameters;
import com.loopers.batch.job.ranking.WeeklyRankingSnapshotRepository;
import com.loopers.batch.job.ranking.WeeklyRankingSnapshotRepository.SnapshotManifest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * 그 주의 구매 활동을 snapshot 입력으로 복사하고 manifest 를 FROZEN 으로 남긴다.
 * step 의 TX 하나에서 읽고 쓰므로, 실패하면 반쪽 snapshot 없이 전부 rollback 된다 (9주차 D-177, D-178)
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class FreezeInputTasklet implements Tasklet {

    private final WeeklyRankingSnapshotRepository snapshots;
    private final PurchaseActivitySource source;
    private final Clock clock;

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        WeeklyRankingParameters params = WeeklyRankingParameters.from(contribution.getStepExecution().getJobParameters());
        // TX 의 첫 읽기(아래 manifest 조회)가 read view 를 정하므로 그 직전에 적는다. 표시 · 기록용 (D-179)
        LocalDateTime frozenAt = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);

        Optional<SnapshotManifest> existing = snapshots.find(params.snapshotId());
        if (existing.isPresent()) {
            // 입력 commit 뒤 step 완료 기록 전에 멈춘 경우. 고정한 입력은 다시 복사하지 않는다
            if (!existing.get().sameIdentityAs(params)) {
                throw new IllegalStateException("같은 snapshotId 의 기간 · revision 이 다름. existing=" + existing.get());
            }
            log.info("이미 고정된 입력을 그대로 씀. snapshotId={} inputCount={}", params.snapshotId(), existing.get().inputCount());
            return RepeatStatus.FINISHED;
        }

        List<PurchaseInput> inputs = source.findConfirmedBetween(params.periodStart(), params.periodEnd());
        snapshots.insertInputs(params.snapshotId(), inputs, frozenAt);
        snapshots.insertFrozen(params, frozenAt, inputs.size(), InputChecksum.of(inputs));
        contribution.incrementWriteCount(inputs.size());
        log.info("입력 고정. snapshotId={} inputCount={}", params.snapshotId(), inputs.size());
        return RepeatStatus.FINISHED;
    }
}
