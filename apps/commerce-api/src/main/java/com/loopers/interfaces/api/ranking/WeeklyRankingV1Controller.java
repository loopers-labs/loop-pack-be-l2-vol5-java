package com.loopers.interfaces.api.ranking;

import com.loopers.application.ranking.WeeklyRankingFacade;
import com.loopers.interfaces.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/rankings")
public class WeeklyRankingV1Controller implements WeeklyRankingV1ApiSpec {

    private final WeeklyRankingFacade weeklyRankingFacade;

    @GetMapping("/weekly")
    @Override
    public ApiResponse<WeeklyRankingV1Dto.WeeklyRankingResponse> getWeekly(
        // ISO.DATE는 날짜 뒤의 시간대 표기(2026-08-17Z, +09:00)를 받고 버린다. 계약은 yyyy-MM-dd 하나라 패턴으로 엄격하게 받는다.
        @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate periodStart,
        @RequestParam(defaultValue = "10") int limit
    ) {
        return ApiResponse.success(
            WeeklyRankingV1Dto.WeeklyRankingResponse.from(weeklyRankingFacade.getWeeklyRanking(periodStart, limit)));
    }
}
