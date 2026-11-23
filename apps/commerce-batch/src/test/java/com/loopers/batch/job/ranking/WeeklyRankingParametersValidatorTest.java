package com.loopers.batch.job.ranking;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.JobParametersInvalidException;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WeeklyRankingParametersValidatorTest {

    private final WeeklyRankingParametersValidator validator = new WeeklyRankingParametersValidator();

    @DisplayName("문자열 식별 파라미터 periodStart(월요일)와 revision이면 통과한다.")
    @Test
    void acceptsValidParameters() {
        assertThatCode(() -> validator.validate(WeeklyRankingTestSupport.parameters("2026-08-17", "1")))
            .doesNotThrowAnyException();
    }

    @DisplayName("revision이 01이거나 0이면 거절한다.")
    @Test
    void rejectsRevisionShapes() {
        assertInvalid(WeeklyRankingTestSupport.parameters("2026-08-17", "01"));
        assertInvalid(WeeklyRankingTestSupport.parameters("2026-08-17", "0"));
    }

    @DisplayName("periodStart가 월요일이 아니면 거절한다.")
    @Test
    void rejectsNonMonday() {
        assertInvalid(WeeklyRankingTestSupport.parameters("2026-08-19", "1"));
    }

    @DisplayName("모르는 파라미터가 있으면 거절한다.")
    @Test
    void rejectsUnknownParameter() {
        assertInvalid(new JobParametersBuilder(WeeklyRankingTestSupport.parameters("2026-08-17", "1"))
            .addString("requestDate", "2026-08-24")
            .toJobParameters());
    }

    @DisplayName("식별이 아닌 파라미터로 오면 거절한다.")
    @Test
    void rejectsNonIdentifyingParameter() {
        assertInvalid(new JobParametersBuilder()
            .addString("periodStart", "2026-08-17")
            .addString("revision", "1", false)
            .toJobParameters());
    }

    @DisplayName("타입이 붙은 파라미터로 오면 거절한다.")
    @Test
    void rejectsTypedParameter() {
        assertInvalid(new JobParametersBuilder()
            .addString("periodStart", "2026-08-17")
            .addLong("revision", 1L)
            .toJobParameters());
    }

    @DisplayName("필요한 파라미터가 없으면 거절한다.")
    @Test
    void rejectsMissingParameter() {
        assertInvalid(new JobParametersBuilder().addString("periodStart", "2026-08-17").toJobParameters());
        assertInvalid(new JobParameters());
    }

    private void assertInvalid(JobParameters parameters) {
        assertThatThrownBy(() -> validator.validate(parameters)).isInstanceOf(JobParametersInvalidException.class);
    }
}
