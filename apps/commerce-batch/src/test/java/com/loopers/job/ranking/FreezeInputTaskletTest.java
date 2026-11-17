package com.loopers.job.ranking;

import com.loopers.batch.job.ranking.FreezeInputTasklet;
import com.loopers.batch.job.ranking.SnapshotInfo;
import com.loopers.batch.job.ranking.SourcePurchase;
import com.loopers.batch.job.ranking.WeeklyRankingParameters;
import com.loopers.batch.job.ranking.WeeklyRankingRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.repeat.RepeatStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

// Step 1의 "같은 snapshotId가 이미 고정돼 있을 때" 분기(D148). Job 경로에서는 파라미터 검증이 날짜 불일치를 먼저 거절하므로,
// 이 분기는 tasklet을 직접 실행해 확인한다. 저장소는 fake로 두고 원본 읽기·INSERT 호출이 있었는지 기록한다.
class FreezeInputTaskletTest {
    private static final String SNAPSHOT_ID = "weekly-20260817-r3";
    private static final SnapshotInfo FROZEN = new SnapshotInfo(SNAPSHOT_ID, LocalDate.parse("2026-08-17"),
        LocalDate.parse("2026-08-24"), 3, Instant.parse("2026-08-24T00:10:00Z"), 4, "checksum");

    @ParameterizedTest(name = "요청 periodStart={0}, revision={1}")
    @CsvSource({"2026-08-24, 3", "2026-08-17, 4"})
    @DisplayName("같은 snapshotId가 다른 기간이나 다른 revision으로 이미 고정돼 있으면 덮어쓰지 않고 실패하며 원본을 읽거나 INSERT하지 않는다")
    void existingSnapshotWithDifferentPeriodOrRevisionFails(String periodStart, int revision) {
        RecordingRepository repository = new RecordingRepository(FROZEN);
        FreezeInputTasklet tasklet = tasklet(repository, new WeeklyRankingParameters(LocalDate.parse(periodStart), SNAPSHOT_ID, revision));

        Throwable failure = catchThrowable(() -> tasklet.execute(contribution(), null));

        assertThat(failure).isInstanceOf(IllegalStateException.class).hasMessageContaining("다른 기간·revision");
        assertThat(repository.calls).isEmpty();
    }

    @ParameterizedTest(name = "요청 periodStart={0}, revision={1}")
    @CsvSource({"2026-08-17, 3"})
    @DisplayName("같은 snapshotId가 같은 기간·revision으로 이미 고정돼 있으면 그대로 끝내고 원본을 읽거나 INSERT하지 않는다")
    void existingSnapshotWithSamePeriodAndRevisionIsKept(String periodStart, int revision) throws Exception {
        RecordingRepository repository = new RecordingRepository(FROZEN);
        FreezeInputTasklet tasklet = tasklet(repository, new WeeklyRankingParameters(LocalDate.parse(periodStart), SNAPSHOT_ID, revision));

        RepeatStatus status = tasklet.execute(contribution(), null);

        assertThat(status).isEqualTo(RepeatStatus.FINISHED);
        assertThat(repository.calls).isEmpty();
    }

    private static FreezeInputTasklet tasklet(WeeklyRankingRepository repository, WeeklyRankingParameters parameters) {
        return new FreezeInputTasklet(repository, Clock.fixed(Instant.parse("2026-08-25T00:00:00Z"), ZoneOffset.UTC), parameters);
    }

    private static StepContribution contribution() {
        return new StepContribution(new StepExecution("freezeInput", new JobExecution(1L)));
    }

    private static class RecordingRepository extends WeeklyRankingRepository {
        private final SnapshotInfo existing;
        private final List<String> calls = new ArrayList<String>();

        RecordingRepository(SnapshotInfo existing) {
            super(null);
            this.existing = existing;
        }

        @Override
        public Optional<SnapshotInfo> findInfo(String snapshotId) {
            return Optional.ofNullable(existing);
        }

        @Override
        public List<SourcePurchase> readSource(LocalDate periodStart, LocalDate periodEnd) {
            calls.add("readSource");
            return List.of();
        }

        @Override
        public void insertInputs(String snapshotId, List<SourcePurchase> rows) {
            calls.add("insertInputs");
        }

        @Override
        public void insertInfo(SnapshotInfo info) {
            calls.add("insertInfo");
        }
    }
}
