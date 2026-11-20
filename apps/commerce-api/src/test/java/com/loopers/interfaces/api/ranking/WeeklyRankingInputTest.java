package com.loopers.interfaces.api.ranking;

import com.loopers.application.ranking.WeeklyRankingInfo;
import com.loopers.application.ranking.WeeklyRankingQueryService;
import com.loopers.interfaces.api.commerce.InvalidRequestException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WeeklyRankingInputTest {
    private final WeeklyRankingQueryService service = mock(WeeklyRankingQueryService.class);
    private final WeeklyRankingController controller = new WeeklyRankingController(service);

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "2026-8-17", "2026-08-18", "2026-02-30", "2026-08-17T00:00:00Z", " 2026-08-17"})
    void requiresAnExactValidIsoMonday(String input) {
        assertThatThrownBy(() -> controller.get(input, null)).isInstanceOf(InvalidRequestException.class);
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "0", "-1", "101", "1.0", "abc", "2147483648", "999999999999999999999"})
    void doesNotClampOrDefaultInvalidLimit(String input) {
        assertThatThrownBy(() -> controller.get("2026-08-17", input)).isInstanceOf(InvalidRequestException.class);
        verifyNoInteractions(service);
    }

    @Test
    void defaultsToTenAndAllowsOneHundredWithoutAnIdentityHeader() {
        LocalDate week = LocalDate.parse("2026-08-17");
        var result = new WeeklyRankingInfo("PUBLISHED", week, week.plusDays(7), "weekly-20260817-r1", 1,
            Instant.parse("2026-08-24T00:00:00Z"), List.of());
        when(service.query(week, 10)).thenReturn(result);
        assertThat(controller.get("2026-08-17", null).data()).isEqualTo(result);
        controller.get("2026-08-17", "100");
        verify(service).query(week, 100);
    }
}
