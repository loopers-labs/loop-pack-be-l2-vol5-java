package com.loopers.domain.ranking;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RankingPeriodTest {

    @DisplayName("월요일로 기간을 만들고, 끝은 다음 월요일이다.")
    @Test
    void createsWeekFromMonday() {
        RankingPeriod period = RankingPeriod.of(LocalDate.parse("2026-08-17"));

        assertThat(period.start()).isEqualTo(LocalDate.parse("2026-08-17"));
        assertThat(period.end()).isEqualTo(LocalDate.parse("2026-08-24"));
    }

    @DisplayName("월요일이 아니면 잘못된 요청으로 거절한다.")
    @Test
    void rejectsNonMonday() {
        assertThatThrownBy(() -> RankingPeriod.of(LocalDate.parse("2026-08-19")))
            .isInstanceOfSatisfying(CoreException.class,
                e -> assertThat(e.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST));
    }
}
