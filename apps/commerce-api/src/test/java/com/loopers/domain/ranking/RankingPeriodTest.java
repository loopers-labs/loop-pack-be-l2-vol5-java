package com.loopers.domain.ranking;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RankingPeriodTest {

    @DisplayName("월요일이면 [그 날, 다음 월요일) 기간이다.")
    @Test
    void mondayStartsWeek() {
        RankingPeriod period = RankingPeriod.parse("2026-08-17");

        assertThat(period.start()).isEqualTo(LocalDate.parse("2026-08-17"));
        assertThat(period.end()).isEqualTo(LocalDate.parse("2026-08-24"));
    }

    @DisplayName("없음·빈 값·형식 오류·없는 날짜·월요일이 아닌 날은 INVALID_RANKING_PERIOD.")
    @Test
    void rejectsInvalid() {
        for (String value : new String[]{null, "", " ", "2026-8-17", "2026-02-30", "2026-08-18", "2026-08-23"}) {
            assertThatThrownBy(() -> RankingPeriod.parse(value))
                .isInstanceOfSatisfying(CoreException.class, e -> assertThat(e.getErrorType()).isEqualTo(ErrorType.INVALID_RANKING_PERIOD));
        }
    }
}
