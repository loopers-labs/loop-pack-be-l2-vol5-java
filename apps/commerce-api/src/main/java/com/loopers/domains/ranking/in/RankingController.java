package com.loopers.domains.ranking.in;

import com.loopers.common.in.ApiResponse;
import com.loopers.common.in.error.CoreException;
import com.loopers.common.in.error.ErrorType;
import com.loopers.domains.ranking.app.WeeklyRankingQueryService;
import com.loopers.domains.ranking.app.WeeklyRankingResult;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// 비로그인 공개다(w9s3-1). X-USER-ID를 요구하지 않고, 와도 읽지 않는다.
@RestController
public class RankingController {
    private final WeeklyRankingQueryService weeklyRankingQueryService;

    public RankingController(WeeklyRankingQueryService weeklyRankingQueryService) {
        this.weeklyRankingQueryService = weeklyRankingQueryService;
    }

    @GetMapping("/api/v1/rankings/weekly")
    public ApiResponse<WeeklyRankingResult> weekly(@RequestParam String periodStart,
        @RequestParam(defaultValue = "10") int limit) {
        if (limit < 1 || limit > 100) {
            throw new CoreException(ErrorType.BAD_REQUEST);
        }
        return ApiResponse.success(weeklyRankingQueryService.weekly(monday(periodStart), limit));
    }

    private static LocalDate monday(String value) {
        LocalDate date;
        try {
            date = LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new CoreException(ErrorType.BAD_REQUEST);
        }
        if (date.getDayOfWeek() != DayOfWeek.MONDAY) {
            throw new CoreException(ErrorType.BAD_REQUEST);
        }
        return date;
    }
}
