package com.loopers.batch.job.ranking.coordination;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;


class WeeklySchedulerContractTest {
    @Test
    @DisplayName("UTC 월요일 00:01 직전과 직후에 가장 최근 닫힌 주차가 달라진다")
    void settlementBoundary() {
        assertThat(WeeklySchedulerConfiguration.latestClosedWeek(clock("2026-08-24T00:00:59Z")))
            .isEqualTo(LocalDate.parse("2026-08-10"));
        assertThat(WeeklySchedulerConfiguration.latestClosedWeek(clock("2026-08-24T00:01:00Z")))
            .isEqualTo(LocalDate.parse("2026-08-17"));
    }

    @Test
    @DisplayName("스케줄러를 local 활성 상태로 시작하면 datasource가 열리기 전 초기화 설정을 거절한다")
    void failsBeforeUnsafeDatasourceStarts() {
        assertRequiresPreserve("--spring.profiles.active=local,ranking-scheduler");
    }

    @Test
    @DisplayName("local에서 자동 실행을 꺼도 주문 파티션 Job을 직접 선택하려면 보존 프로필이 필요하다")
    void rejectsStandaloneOrderPartitionInLocal() {
        assertRequiresPreserve("--spring.profiles.active=local", "--spring.batch.job.name=weeklyRankingOrderPartition",
            "--spring.batch.job.enabled=false");
    }

    private void assertRequiresPreserve(String... arguments) {
        assertThatThrownBy(() -> {
            try (var ignored = new SpringApplicationBuilder(EmptyConfiguration.class).web(WebApplicationType.NONE).run(arguments)) {
                // This context deliberately has no datasource or other infrastructure.
            }
        }).hasStackTraceContaining("ranking-preserve");
    }

    @Test
    @DisplayName("실제 커넥션 풀에 두 worker의 커서·트랜잭션과 감사용 여유가 있어야 한다")
    void enforcesResidentPoolCapacity() {
        assertThatThrownBy(() -> WeeklySchedulerConfiguration.Resident.requireCapacity(2, 6))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("7");
        WeeklySchedulerConfiguration.Resident.requireCapacity(2, 7);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class EmptyConfiguration { }

    private Clock clock(String instant) {
        return Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
    }
}
