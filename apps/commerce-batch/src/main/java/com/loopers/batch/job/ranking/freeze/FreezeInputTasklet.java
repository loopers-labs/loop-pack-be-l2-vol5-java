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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
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

    // 읽기 확인(50만 행)에서 1000 · 5000 · 20000 의 차이가 1초 안이라 중간값을 둔다
    @Value("${weekly-ranking.freeze-page-size:5000}")
    private int pageSize;

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

        // 한 주 입력을 통째로 들면 50만 행에서 힙 512MB 를 넘었다. 같은 TX 안에서 페이지마다 쓰고 checksum 에 흘려 넣는다
        InputChecksum checksum = new InputChecksum();
        int[] count = {0};
        source.readConfirmedBetween(params.periodStart(), params.periodEnd(), pageSize, page -> {
            snapshots.insertInputs(params.snapshotId(), page, frozenAt);
            page.forEach(checksum::add);
            count[0] += page.size();
        });
        snapshots.insertFrozen(params, frozenAt, count[0], checksum.hex());
        contribution.incrementWriteCount(count[0]);
        log.info("입력 고정. snapshotId={} inputCount={}", params.snapshotId(), count[0]);
        return RepeatStatus.FINISHED;
    }
}
