package com.loopers.batch.job.ranking.step;

import com.loopers.batch.job.ranking.WeeklyRankingJobConfig;
import com.loopers.batch.job.ranking.WeeklyRankingStore;
import com.loopers.batch.job.ranking.WeeklyRankingStore.InputSummary;
import com.loopers.batch.job.ranking.WeeklyRankingStore.SnapshotManifest;
import com.loopers.batch.job.ranking.WeeklySnapshotId;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 입력 고정. 기간 안의 구매 활동을 snapshot 입력으로 복사하고, 건수·상품 수·checksum과 snapshot 정보를 같은 TX로 저장한다.
 * <ul>
 *   <li>처음 보는 snapshotId: revision이 그 기간의 마지막 revision + 1일 때만 만든다(건너뛰기 금지).
 *       그 기간의 이전 revision이 아직 완성되지 않았으면 시작하지 않는다(같은 기간의 계산은 하나씩).</li>
 *   <li>이미 있는 snapshotId: 다시 복사하지 않는다. 저장된 기간·revision·입력 요약이 같으면 그대로 쓰고, 다르면 충돌로 실패한다.</li>
 *   <li>복사한 입력에 수량이 0 이하인 행이 있으면 건너뛰지 않고 실패한다. 같은 TX라 복사본도 snapshot 정보도 남지 않는다.</li>
 * </ul>
 * 이미 있는 경우는 입력 고정 TX는 commit됐는데 Step 완료 기록만 실패했을 때 재시작에서 생길 수 있다.
 */
@StepScope
@ConditionalOnProperty(name = "spring.batch.job.name", havingValue = WeeklyRankingJobConfig.JOB_NAME)
@RequiredArgsConstructor
@Component
public class FreezeSnapshotTasklet implements Tasklet {
    private final WeeklyRankingStore store;

    @Value("#{jobParameters['snapshotId']}")
    private String snapshotIdParameter;

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        WeeklySnapshotId snapshotId = WeeklySnapshotId.parse(snapshotIdParameter);

        Optional<SnapshotManifest> existing = store.findSnapshot(snapshotId.value());
        if (existing.isPresent()) {
            verifySameSnapshot(existing.get(), snapshotId);
            return RepeatStatus.FINISHED;
        }

        int expectedRevision = store.lastRevision(snapshotId.periodStart()) + 1;
        if (snapshotId.revision() != expectedRevision) {
            throw new IllegalStateException("revision은 하나씩 올려야 합니다. " + snapshotId.periodStart()
                + "의 다음 revision은 r" + expectedRevision + "인데 " + snapshotId.value() + "를 받았습니다.");
        }
        if (store.hasUnfinishedRevision(snapshotId.periodStart())) {
            throw new IllegalStateException(snapshotId.periodStart() + "의 이전 revision이 아직 완성되지 않았습니다. "
                + "그 revision을 같은 parameter로 재시작해 끝낸 뒤 " + snapshotId.value() + "를 실행하세요.");
        }

        LocalDateTime frozenAt = store.currentUtcTime();
        store.copyInputs(snapshotId);
        int invalidRows = store.nonPositiveQuantityRows(snapshotId.value());
        if (invalidRows > 0) {
            throw new IllegalStateException(snapshotId.value() + "의 입력에 수량이 0 이하인 구매 활동이 " + invalidRows
                + "건 있습니다. 원본을 고친 뒤 같은 parameter로 다시 실행하세요.");
        }
        InputSummary summary = store.summarizeInputs(snapshotId.value());
        store.insertSnapshot(snapshotId, frozenAt, summary);
        contribution.incrementWriteCount(summary.inputCount());
        return RepeatStatus.FINISHED;
    }

    private void verifySameSnapshot(SnapshotManifest manifest, WeeklySnapshotId snapshotId) {
        InputSummary stored = store.summarizeInputs(manifest.snapshotId());
        boolean sameIdentity = manifest.periodStart().equals(snapshotId.periodStart())
            && manifest.revision() == snapshotId.revision();
        boolean sameInput = stored.equals(manifest.inputSummary());
        if (!sameIdentity || !sameInput) {
            throw new IllegalStateException("이미 있는 snapshot과 기간·revision·입력이 다릅니다: " + manifest
                + ", 저장된 입력 요약=" + stored);
        }
    }
}
