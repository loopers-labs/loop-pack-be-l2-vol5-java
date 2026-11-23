package com.loopers.interfaces.api.ranking;

import com.loopers.application.ranking.GetWeeklyRankingFacade;
import com.loopers.interfaces.api.ApiResponse;

import lombok.RequiredArgsConstructor;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequiredArgsConstructor
public class GetWeeklyRankingController {
    private final GetWeeklyRankingFacade facade;

    @GetMapping("/api/v1/rankings/weekly")
    public ApiResponse<WeeklyRankingDto.Response> weekly(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodStart,
            @RequestParam(defaultValue = "10") int limit) {
        return ApiResponse.success(WeeklyRankingDto.Response.from(facade.get(periodStart, limit)));
    }
}
