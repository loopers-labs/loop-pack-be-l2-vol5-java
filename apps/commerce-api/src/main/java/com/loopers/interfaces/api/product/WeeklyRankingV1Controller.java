package com.loopers.interfaces.api.product;

import com.loopers.application.product.facade.WeeklyRankingFacade;
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
    public ApiResponse<WeeklyRankingV1Dto.WeeklyRankingResponse> getWeeklyRanking(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodStart,
        @RequestParam(defaultValue = "10") int limit
    ) {
        return ApiResponse.success(WeeklyRankingV1Dto.WeeklyRankingResponse.from(weeklyRankingFacade.getWeeklyRanking(periodStart, limit)));
    }
}
