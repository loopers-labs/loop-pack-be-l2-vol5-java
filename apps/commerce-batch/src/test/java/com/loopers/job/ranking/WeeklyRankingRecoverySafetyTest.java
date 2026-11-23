package com.loopers.job.ranking;

import com.loopers.batch.job.ranking.WeeklyRankingRecoverySafetyConfig;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class WeeklyRankingRecoverySafetyTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(WeeklyRankingRecoverySafetyConfig.class)
            .withPropertyValues(
                    "weekly-ranking.recovery.execution-id=1",
                    "spring.batch.job.name=weeklyRanking",
                    "spring.batch.job.enabled=false",
                    "spring.batch.jdbc.initialize-schema=never",
                    "weekly-ranking.recovery.termination-confirmed=true");

    @Test
    @DisplayName("복구 모드에서 JPA 테이블 자동 생성이 켜져 있으면 시작을 거부한다")
    void rejectsJpaSchemaCreation() {
        context.withPropertyValues("spring.jpa.hibernate.ddl-auto=create")
                .run(result -> assertThat(result.getStartupFailure())
                        .hasMessage("recovery requires spring.jpa.hibernate.ddl-auto=none"));
    }

    @Test
    @DisplayName("복구 실행 ID를 잘못 입력해도 JPA 초기화 안전검사를 건너뛰지 않는다")
    void invalidExecutionIdDoesNotBypassSafety() {
        context.withPropertyValues("weekly-ranking.recovery.execution-id=false", "spring.jpa.hibernate.ddl-auto=create")
                .run(result -> assertThat(result.getStartupFailure())
                        .hasMessage("recovery requires spring.jpa.hibernate.ddl-auto=none"));
    }

    @Test
    @DisplayName("복구 모드에서 Batch 테이블 자동 생성이 켜져 있으면 시작을 거부한다")
    void rejectsBatchSchemaCreation() {
        context.withPropertyValues("spring.jpa.hibernate.ddl-auto=none", "spring.batch.jdbc.initialize-schema=always")
                .run(result -> assertThat(result.getStartupFailure())
                        .hasMessage("recovery requires spring.batch.jdbc.initialize-schema=never"));
    }

    @Test
    @DisplayName("복구 모드에서 이전 프로세스의 종료 확인이 없으면 시작을 거부한다")
    void rejectsMissingTerminationConfirmation() {
        context.withPropertyValues("spring.jpa.hibernate.ddl-auto=none", "weekly-ranking.recovery.termination-confirmed=false")
                .run(result -> assertThat(result.getStartupFailure())
                        .hasMessage("recovery requires weekly-ranking.recovery.termination-confirmed=true"));
    }

    @Test
    @DisplayName("복구 모드에서 Job 자동 실행이 켜져 있으면 시작을 거부한다")
    void rejectsAutomaticJobLaunch() {
        context.withPropertyValues("spring.jpa.hibernate.ddl-auto=none", "spring.batch.job.enabled=true")
                .run(result -> assertThat(result.getStartupFailure())
                        .hasMessage("recovery requires spring.batch.job.enabled=false"));
    }

    @Test
    @DisplayName("복구 안전 조건을 모두 명시하면 시작을 허용한다")
    void acceptsExplicitSafeSettings() {
        context.withPropertyValues("spring.jpa.hibernate.ddl-auto=none")
                .run(result -> assertThat(result).hasNotFailed());
    }
}
