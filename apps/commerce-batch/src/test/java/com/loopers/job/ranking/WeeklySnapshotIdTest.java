package com.loopers.job.ranking;

import com.loopers.batch.job.ranking.RowChecksum;
import com.loopers.batch.job.ranking.WeeklyRankingParametersValidator;
import com.loopers.batch.job.ranking.WeeklySnapshotId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.JobParametersInvalidException;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WeeklySnapshotIdTest {

    @DisplayName("snapshotId는 UTC 월요일 기간과 revision을 담고, 기간은 시작 포함·다음 월요일 제외다.")
    @Test
    void parsesPeriodAndRevision() {
        WeeklySnapshotId id = WeeklySnapshotId.parse("weekly-20260817-r2");

        assertThat(id.periodStart()).isEqualTo(LocalDate.of(2026, 8, 17));
        assertThat(id.revision()).isEqualTo(2);
        assertThat(id.value()).isEqualTo("weekly-20260817-r2");
        assertThat(id.startInclusive()).isEqualTo(LocalDateTime.parse("2026-08-17T00:00:00"));
        assertThat(id.endExclusive()).isEqualTo(LocalDateTime.parse("2026-08-24T00:00:00"));
    }

    @DisplayName("형식이 다르거나, 월요일이 아니거나, revision이 1보다 작으면 거부한다.")
    @Test
    void rejectsInvalidSnapshotId() {
        assertThatThrownBy(() -> WeeklySnapshotId.parse("weekly-20260818-r1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WeeklySnapshotId.parse("weekly-20260817-r0")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WeeklySnapshotId.parse("weekly-20261317-r1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WeeklySnapshotId.parse("probe-1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WeeklySnapshotId.parse(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @DisplayName("checksum은 행의 값을 TAB·LF로 직렬화한 SHA-256이다. 입력은 orderId·productId·quantity·UTC confirmedAt, 결과는 productId·score·rank다.")
    @Test
    void checksumFollowsFixedSerialization() {
        RowChecksum checksum = new RowChecksum();
        checksum.addRow(42, 7, 2, RowChecksum.utc(LocalDateTime.parse("2026-08-17T01:00:00")));
        checksum.addRow(42, 8, 1, RowChecksum.utc(LocalDateTime.parse("2026-08-17T01:00:00")));
        checksum.addRow(43, 7, 3, RowChecksum.utc(LocalDateTime.parse("2026-08-18T01:00:00")));
        checksum.addRow(44, 9, 5, RowChecksum.utc(LocalDateTime.parse("2026-08-19T01:00:00")));

        // printf '42\t7\t2\t2026-08-17T01:00:00.000000Z\n...44\t9\t5\t2026-08-19T01:00:00.000000Z\n' | shasum -a 256
        assertThat(checksum.hex()).isEqualTo("3e90276ec1f9123f308cd18d155e1dde6bf811ef3aa741105ff5c766c357a298");
        assertThat(new RowChecksum().hex()).isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");

        // 결과: 순위 순서의 productId, score, rank. printf '7\t5\t1\n9\t5\t2\n8\t1\t3\n' | shasum -a 256
        RowChecksum result = new RowChecksum();
        result.addRow(7, 5, 1);
        result.addRow(9, 5, 2);
        result.addRow(8, 1, 3);
        assertThat(result.hex()).isEqualTo("745d50bbd2f04a2e8c937f9ebf5f2cddbf75b958540e964934a424903b0a67d2");
    }

    @DisplayName("job parameter는 식별용 periodStart·snapshotId만 받고, 날짜가 서로 같아야 한다.")
    @Test
    void validatesJobParameters() {
        var validator = new WeeklyRankingParametersValidator();

        assertThatCode(() -> validator.validate(params("2026-08-17", "weekly-20260817-r1"))).doesNotThrowAnyException();
        assertThatThrownBy(() -> validator.validate(params("2026-08-24", "weekly-20260817-r1")))
            .isInstanceOf(JobParametersInvalidException.class);
        assertThatThrownBy(() -> validator.validate(params("2026-08-18", "weekly-20260817-r1")))
            .isInstanceOf(JobParametersInvalidException.class);
        assertThatThrownBy(() -> validator.validate(new JobParametersBuilder(params("2026-08-17", "weekly-20260817-r1"))
            .addLong("run.id", 1L).toJobParameters()))
            .isInstanceOf(JobParametersInvalidException.class);
        assertThatThrownBy(() -> validator.validate(new JobParametersBuilder()
            .addString("periodStart", "2026-08-17", true)
            .addString("snapshotId", "weekly-20260817-r1", false).toJobParameters()))
            .isInstanceOf(JobParametersInvalidException.class);
    }

    private static JobParameters params(String periodStart, String snapshotId) {
        return new JobParametersBuilder()
            .addString("periodStart", periodStart, true)
            .addString("snapshotId", snapshotId, true)
            .toJobParameters();
    }
}
