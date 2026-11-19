package com.loopers.ranking;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class WeeklyRankingPlanSchemaTest {
    @Test
    @DisplayName("운영자가 계획·멤버십·실행권을 보존하는 증분 스키마를 적용할 수 있다")
    void providesPlanMigration() {
        assertThat(new ClassPathResource("preservation/017-weekly-ranking-plans.sql").exists()).isTrue();
    }
}
