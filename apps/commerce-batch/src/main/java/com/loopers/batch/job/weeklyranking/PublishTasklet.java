package com.loopers.batch.job.weeklyranking;

import com.loopers.batch.domain.SnapshotId;
import com.loopers.batch.job.weeklyranking.RankingPublishJdbcRepository.Publication;
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

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * 공개 step (W9 D-5). step이 연 TX 하나 안에서 아래를 한 번에 한다. 하나라도 실패하면 모두 rollback되고 이전 공개본이 그대로 보인다.
 * ① 완성 확인 (점수 행 수 = 고정 입력의 상품 수) → ② 순위 매기기 → ③ snapshot COMPLETED
 * → ④ 그 주 pointer를 잠그고 revision 비교: 없음·작음이면 이 snapshot으로, 이미 이 snapshot이면 그대로,
 *    더 새 revision이 공개돼 있으면 바꾸지 않고 ExitStatus REPLACED로 끝낸다.
 */
@Slf4j
@StepScope
@ConditionalOnProperty(name = "spring.batch.job.name", havingValue = WeeklyRankingJobConfig.JOB_NAME)
@RequiredArgsConstructor
@Component
public class PublishTasklet implements Tasklet {

    public static final String REPLACED = "REPLACED";

    private final RankingPublishJdbcRepository repository;
    private final Clock clock;

    @Value("#{jobParameters['periodStart']}")
    private LocalDate periodStart;

    @Value("#{jobParameters['revision']}")
    private Long revision;

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        String snapshotId = SnapshotId.of(periodStart, revision).value();
        LocalDateTime nowUtc = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);

        long scored = repository.countScores(snapshotId);
        long products = repository.countInputProducts(snapshotId);
        if (scored != products) {
            throw new IllegalStateException("점수 행 수가 고정 입력의 상품 수와 달라 공개하지 않습니다. [snapshotId = " + snapshotId
                + ", 점수 행 = " + scored + ", 상품 수 = " + products + "]");
        }
        repository.assignRanks(snapshotId, nowUtc);
        repository.markCompleted(snapshotId, nowUtc);

        Optional<Publication> current = repository.lockPublication(periodStart);
        if (current.isEmpty()) {
            repository.insertPublication(periodStart, snapshotId, revision, nowUtc);
            log.info("[공개] 그 주 첫 공개입니다. [periodStart = {}, snapshotId = {}]", periodStart, snapshotId);
        } else if (current.get().snapshotId().equals(snapshotId)) {
            log.info("[공개] 이미 이 snapshot이 공개돼 있어 pointer를 그대로 둡니다. [snapshotId = {}]", snapshotId);
        } else if (current.get().revision() < revision) {
            repository.updatePublication(periodStart, snapshotId, revision, nowUtc);
            log.info("[공개] pointer를 바꿨습니다. [periodStart = {}, {} → {}]", periodStart, current.get().snapshotId(), snapshotId);
        } else {
            contribution.setExitStatus(new ExitStatus(REPLACED, "더 새 revision이 이미 공개돼 있어 pointer를 바꾸지 않았습니다. [공개 중 = "
                + current.get().snapshotId() + ", 이 실행 = " + snapshotId + "]"));
            log.info("[공개] 더 새 revision이 이미 공개돼 있어 pointer를 바꾸지 않습니다. [공개 중 = {}, 이 실행 = {}]", current.get().snapshotId(), snapshotId);
        }
        return RepeatStatus.FINISHED;
    }
}
