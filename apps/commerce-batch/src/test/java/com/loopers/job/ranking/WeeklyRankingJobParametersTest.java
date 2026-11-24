package com.loopers.job.ranking;

import com.loopers.batch.job.weeklyranking.WeeklyRankingJobParameters;
import com.loopers.domain.ranking.WeeklyRankingKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.batch.core.JobParameter;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.JobParametersInvalidException;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WeeklyRankingJobParametersTest {
    private static final WeeklyRankingKey KEY = new WeeklyRankingKey(LocalDate.of(2026, 8, 17), 1);
    private final WeeklyRankingJobParameters validator = new WeeklyRankingJobParameters();

    @Test
    void factoryBuildsExactlyThreeStableIdentifyingParametersWithExplicitTypes() {
        JobParameters parameters = WeeklyRankingJobParameters.parameters(KEY);

        assertThat(parameters.getParameters()).containsOnlyKeys("periodStart", "revision", "snapshotId");
        assertThat(parameters.getString("periodStart")).isEqualTo("2026-08-17");
        assertThat(parameters.getLong("revision")).isEqualTo(1L);
        assertThat(parameters.getString("snapshotId")).isEqualTo("weekly-20260817-r1");
        assertThat(parameters.getIdentifyingParameters()).isEqualTo(parameters.getParameters());
        assertThat(WeeklyRankingJobParameters.parameters(new WeeklyRankingKey(KEY.periodStart(), 1))).isEqualTo(parameters);
        assertThat(WeeklyRankingJobParameters.parameters(new WeeklyRankingKey(KEY.periodStart(), 2))).isNotEqualTo(parameters);
    }

    @Test
    void acceptsTheCanonicalTripleAndReturnsTheSameBusinessIdentity() {
        assertThatCode(() -> validator.validate(valid())).doesNotThrowAnyException();
        assertThat(WeeklyRankingJobParameters.key(valid())).isEqualTo(KEY);
    }

    @Test
    void acceptsLargestSupportedRevisionWithoutNarrowingOverflow() {
        var parameters = new JobParametersBuilder()
            .addString("periodStart", "2026-08-17", true)
            .addLong("revision", (long) Integer.MAX_VALUE, true)
            .addString("snapshotId", "weekly-20260817-r2147483647", true).toJobParameters();

        assertThat(WeeklyRankingJobParameters.key(parameters).revision()).isEqualTo(Integer.MAX_VALUE);
        assertThatCode(() -> validator.validate(parameters)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"periodStart", "revision", "snapshotId"})
    void rejectsEveryMissingIdentityField(String field) {
        var fields = new LinkedHashMap<>(valid().getParameters());
        fields.remove(field);

        assertRejected(new JobParameters(fields));
    }

    @ParameterizedTest
    @ValueSource(strings = {"run.id", "failOnNine", "requestedAt", "unknown"})
    void rejectsExtraParametersEvenWhenTheyAreNotIdentifying(String field) {
        var fields = new LinkedHashMap<>(valid().getParameters());
        fields.put(field, new JobParameter<>("private-untrusted-value", String.class, false));

        assertRejected(new JobParameters(fields));
    }

    @ParameterizedTest
    @MethodSource("wrongTypes")
    void rejectsTypeConversionsThatCouldChangeJobIdentity(String field, JobParameter<?> value) {
        assertRejected(replace(field, value));
    }

    @ParameterizedTest
    @ValueSource(strings = {"periodStart", "revision", "snapshotId"})
    void everyBusinessParameterMustParticipateInJobIdentity(String field) {
        JobParameter<?> nonIdentifying = switch (field) {
            case "periodStart" -> new JobParameter<>("2026-08-17", String.class, false);
            case "revision" -> new JobParameter<>(1L, Long.class, false);
            case "snapshotId" -> new JobParameter<>("weekly-20260817-r1", String.class, false);
            default -> throw new IllegalArgumentException("Unknown fixture field");
        };

        assertRejected(replace(field, nonIdentifying));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "2026/08/17", "2026-8-17", " 2026-08-17", "2026-08-17T00:00:00Z", "2026-02-30", "2026-08-18"})
    void rejectsNonCanonicalDatesAndNonMondays(String date) {
        assertRejected(replace("periodStart", new JobParameter<>(date, String.class, true)));
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L, 2147483648L, Long.MAX_VALUE})
    void rejectsNonPositiveAndOutOfRangeRevisions(long revision) {
        assertRejected(replace("revision", new JobParameter<>(revision, Long.class, true)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"weekly-20260817-r2", "weekly-20260824-r1", "private-untrusted-value"})
    void rejectsSnapshotIdentityThatDoesNotMatchItsPeriodAndRevision(String snapshotId) {
        assertRejected(replace("snapshotId", new JobParameter<>(snapshotId, String.class, true)));
    }

    @Test
    void rejectsMissingParameterObjectWithACheckedValidationErrorAndNoInputEcho() {
        assertRejected(null);
    }

    @Test
    void factoryRejectsMissingBusinessIdentity() {
        assertThatThrownBy(() -> WeeklyRankingJobParameters.parameters(null)).isInstanceOf(IllegalArgumentException.class);
    }

    private void assertRejected(JobParameters parameters) {
        assertThatThrownBy(() -> validator.validate(parameters))
            .isInstanceOf(JobParametersInvalidException.class)
            .hasMessageNotContaining("private-untrusted-value");
        assertThatThrownBy(() -> WeeklyRankingJobParameters.key(parameters)).isInstanceOf(IllegalArgumentException.class);
    }

    private static JobParameters valid() {
        return new JobParametersBuilder()
            .addString("periodStart", "2026-08-17", true)
            .addLong("revision", 1L, true)
            .addString("snapshotId", "weekly-20260817-r1", true).toJobParameters();
    }

    private static JobParameters replace(String field, JobParameter<?> value) {
        var fields = new LinkedHashMap<>(valid().getParameters());
        fields.put(field, value);
        return new JobParameters(fields);
    }

    private static Stream<Arguments> wrongTypes() {
        return Stream.of(
            Arguments.of("periodStart", new JobParameter<>(LocalDate.of(2026, 8, 17), LocalDate.class, true)),
            Arguments.of("periodStart", new JobParameter<>(20260817L, Long.class, true)),
            Arguments.of("revision", new JobParameter<>("1", String.class, true)),
            Arguments.of("revision", new JobParameter<>(1, Integer.class, true)),
            Arguments.of("revision", new JobParameter<>(1.0d, Double.class, true)),
            Arguments.of("snapshotId", new JobParameter<>(1L, Long.class, true))
        );
    }
}
