package com.loopers.batch.job.ranking.step;

import com.loopers.batch.job.ranking.WeeklyRankingJobConfig;
import com.loopers.batch.job.ranking.WeeklyRankingStore;
import com.loopers.batch.job.ranking.WeeklyRankingStore.InputSummary;
import com.loopers.batch.job.ranking.WeeklyRankingStore.Publication;
import com.loopers.batch.job.ranking.WeeklyRankingStore.SnapshotManifest;
import com.loopers.batch.job.ranking.WeeklySnapshotId;
import lombok.RequiredArgsConstructor;
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

import java.util.Optional;

/**
 * 완성본 공개. 한 TX 안에서 완성 조건 검증 → 순위 매긴 완성 랭킹 저장 → snapshot 완성 표시 → 공개 pointer 교체를 한다.
 * 실패하면 모두 rollback되어 이전 공개본이 그대로 남는다.
 * <ul>
 *   <li>이미 COMPLETED인 snapshot(공개 TX는 commit됐지만 Step 완료 기록이 실패한 재실행): 완성 랭킹을 다시 만들지 않고
 *       저장된 결과 checksum만 확인한 뒤 pointer 판단을 다시 한다. 같은 결과로 수렴한다.</li>
 *   <li>pointer가 이미 더 새 revision이면 바꾸지 않고 종료 코드 {@value #NOT_PUBLISHED_OLDER_REVISION}로 끝낸다.</li>
 * </ul>
 */
@Slf4j
@StepScope
@ConditionalOnProperty(name = "spring.batch.job.name", havingValue = WeeklyRankingJobConfig.JOB_NAME)
@RequiredArgsConstructor
@Component
public class PublishRankingTasklet implements Tasklet {
    public static final String NOT_PUBLISHED_OLDER_REVISION = "NOT_PUBLISHED_OLDER_REVISION";

    private final WeeklyRankingStore store;

    @Value("#{jobParameters['snapshotId']}")
    private String snapshotIdParameter;

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        WeeklySnapshotId snapshotId = WeeklySnapshotId.parse(snapshotIdParameter);
        SnapshotManifest manifest = store.findSnapshot(snapshotId.value())
            .orElseThrow(() -> new IllegalStateException("입력 고정 정보가 없습니다: " + snapshotId.value()));

        if (WeeklyRankingStore.STATUS_ABANDONED.equals(manifest.status())) {
            throw new IllegalStateException("포기로 표시된 snapshot은 공개하지 않습니다: " + manifest.snapshotId());
        }
        if (WeeklyRankingStore.STATUS_COMPLETED.equals(manifest.status())) {
            verifyStoredResult(manifest);
        } else {
            verifyComplete(manifest);
            store.insertEntries(manifest.snapshotId());
            if (store.markCompleted(manifest.snapshotId(), store.resultChecksum(manifest.snapshotId())) != 1) {
                throw new IllegalStateException("snapshot을 완성으로 표시하지 못했습니다: " + manifest.snapshotId());
            }
        }

        if (!publish(snapshotId)) {
            contribution.setExitStatus(new ExitStatus(NOT_PUBLISHED_OLDER_REVISION));
        }
        return RepeatStatus.FINISHED;
    }

    /** 완성 조건: 상품 수, 점수 합계 = 입력 수량 합계, 고정 입력이 그대로인지(입력 checksum). */
    private void verifyComplete(SnapshotManifest manifest) {
        String id = manifest.snapshotId();
        int stageRows = store.stageRows(id);
        long scoreTotal = store.stageScoreTotal(id);
        long quantityTotal = store.inputQuantityTotal(id);
        InputSummary input = store.summarizeInputs(id);
        if (stageRows != manifest.productCount() || scoreTotal != quantityTotal || !input.equals(manifest.inputSummary())) {
            throw new IllegalStateException("완성 조건을 만족하지 않아 공개하지 않습니다: " + id
                + " (stage 상품 " + stageRows + " / 기대 " + manifest.productCount()
                + ", 점수 합 " + scoreTotal + " / 입력 수량 합 " + quantityTotal
                + ", 입력 요약 " + input + " / 고정 당시 " + manifest.inputSummary() + ")");
        }
    }

    private void verifyStoredResult(SnapshotManifest manifest) {
        String actual = store.resultChecksum(manifest.snapshotId());
        if (!actual.equals(manifest.resultChecksum())) {
            throw new IllegalStateException("완성 랭킹이 저장된 결과 checksum과 다릅니다: " + manifest.snapshotId()
                + " (" + actual + " / " + manifest.resultChecksum() + ")");
        }
    }

    /** pointer를 잠그고, 없거나 더 오래된 revision이면 바꾼다. 같은 snapshot이면 그대로 둔다. 공개 상태면 true. */
    private boolean publish(WeeklySnapshotId snapshotId) {
        Optional<Publication> current = store.lockPublication(snapshotId.periodStart());
        if (current.isEmpty()) {
            store.insertPublication(snapshotId);
            return true;
        }
        Publication publication = current.get();
        if (publication.revision() == snapshotId.revision()) {
            return true;
        }
        if (publication.revision() > snapshotId.revision()) {
            log.warn("{}보다 새 {}가 이미 공개돼 있어 공개 pointer를 바꾸지 않습니다.", snapshotId.value(), publication.snapshotId());
            return false;
        }
        if (store.replacePublication(snapshotId, publication.revision()) != 1) {
            throw new IllegalStateException("공개 pointer를 바꾸지 못했습니다: " + publication + " → " + snapshotId.value());
        }
        return true;
    }
}
