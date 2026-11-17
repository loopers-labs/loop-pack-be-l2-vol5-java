package com.loopers.batch.job.ranking;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;

// Step 1 입력 고정(D148·D149). step TX 하나 안에서 원본 읽기 → 계약 확인 → 건수·checksum → 입력 INSERT → 정보 INSERT.
// 중간에 실패하면 입력 행과 정보 행이 함께 rollback되어 반쪽 snapshot이 남지 않는다. 격리 수준은 기본(REPEATABLE READ) 그대로다.
public class FreezeInputTasklet implements Tasklet {
    private final WeeklyRankingRepository weeklyRankingRepository;
    private final Clock clock;
    private final WeeklyRankingParameters parameters;

    public FreezeInputTasklet(WeeklyRankingRepository weeklyRankingRepository, Clock clock, WeeklyRankingParameters parameters) {
        this.weeklyRankingRepository = weeklyRankingRepository;
        this.clock = clock;
        this.parameters = parameters;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        Optional<SnapshotInfo> existing = weeklyRankingRepository.findInfo(parameters.snapshotId());
        if (existing.isPresent()) {
            return alreadyFrozen(existing.get());
        }
        List<SourcePurchase> rows = weeklyRankingRepository.readSource(parameters.periodStart(), parameters.periodEnd());
        List<SourcePurchase> violations = rows.stream().filter(row -> row.quantity() <= 0).toList();
        if (!violations.isEmpty()) {
            throw new IllegalStateException("수량이 0 이하인 원본 행이 있어 고정하지 않는다. snapshotId=" + parameters.snapshotId()
                + ", 행=" + violations);
        }
        weeklyRankingRepository.insertInputs(parameters.snapshotId(), rows);
        weeklyRankingRepository.insertInfo(new SnapshotInfo(parameters.snapshotId(), parameters.periodStart(),
            parameters.periodEnd(), parameters.revision(), clock.instant(), rows.size(), InputChecksum.of(rows)));
        contribution.incrementWriteCount(rows.size());
        return RepeatStatus.FINISHED;
    }

    // 같은 이름으로 이미 고정돼 있으면 기간·revision이 같을 때만 그대로 쓴다. 다르면 덮어쓰지 않고 실패한다(D148).
    private RepeatStatus alreadyFrozen(SnapshotInfo info) {
        if (info.periodStart().equals(parameters.periodStart()) && info.revision() == parameters.revision()) {
            return RepeatStatus.FINISHED;
        }
        throw new IllegalStateException("같은 snapshotId가 다른 기간·revision으로 이미 고정돼 있다. snapshotId=" + parameters.snapshotId()
            + ", 기존=" + info.periodStart() + "/r" + info.revision()
            + ", 요청=" + parameters.periodStart() + "/r" + parameters.revision());
    }
}
