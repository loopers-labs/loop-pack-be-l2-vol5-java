package com.loopers.batch.job.weeklyranking;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.JobParametersInvalidException;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class WeeklyRankingJobParametersValidatorTest {

    private final WeeklyRankingJobParametersValidator validator = new WeeklyRankingJobParametersValidator();

    @Test
    void periodStart가_월요일이고_revision이_1_이상이면_통과한다() {
        // given
        JobParameters parameters = params(LocalDate.parse("2026-08-17"), 1L);

        // when
        Throwable thrown = catchThrowable(() -> validator.validate(parameters));

        // then
        assertThat(thrown).isNull();
    }

    @Test
    void periodStart가_월요일이_아니면_거절한다() {
        // given: 2026-08-18은 화요일
        JobParameters parameters = params(LocalDate.parse("2026-08-18"), 1L);

        // when
        Throwable thrown = catchThrowable(() -> validator.validate(parameters));

        // then
        assertThat(thrown).isInstanceOf(JobParametersInvalidException.class).hasMessageContaining("월요일");
    }

    @Test
    void revision이_0이면_거절한다() {
        // given
        JobParameters parameters = params(LocalDate.parse("2026-08-17"), 0L);

        // when
        Throwable thrown = catchThrowable(() -> validator.validate(parameters));

        // then
        assertThat(thrown).isInstanceOf(JobParametersInvalidException.class).hasMessageContaining("revision");
    }

    @Test
    void periodStart가_없으면_거절한다() {
        // given
        JobParameters parameters = new JobParametersBuilder().addLong("revision", 1L).toJobParameters();

        // when
        Throwable thrown = catchThrowable(() -> validator.validate(parameters));

        // then
        assertThat(thrown).isInstanceOf(JobParametersInvalidException.class).hasMessageContaining("periodStart");
    }

    @Test
    void revision이_없으면_거절한다() {
        // given
        JobParameters parameters = new JobParametersBuilder().addLocalDate("periodStart", LocalDate.parse("2026-08-17")).toJobParameters();

        // when
        Throwable thrown = catchThrowable(() -> validator.validate(parameters));

        // then
        assertThat(thrown).isInstanceOf(JobParametersInvalidException.class).hasMessageContaining("revision");
    }

    @Test
    void periodStart가_날짜가_아니라_문자열이면_거절한다() {
        // given: 명령에서 타입 없이 넘기면 문자열이 된다 (runner가 바꿔 넣기 전 값)
        JobParameters parameters = new JobParametersBuilder()
            .addString("periodStart", "2026-08-17")
            .addLong("revision", 1L)
            .toJobParameters();

        // when
        Throwable thrown = catchThrowable(() -> validator.validate(parameters));

        // then
        assertThat(thrown).isInstanceOf(JobParametersInvalidException.class).hasMessageContaining("periodStart(LocalDate)");
    }

    private static JobParameters params(LocalDate periodStart, long revision) {
        return new JobParametersBuilder()
            .addLocalDate("periodStart", periodStart)
            .addLong("revision", revision)
            .toJobParameters();
    }
}
