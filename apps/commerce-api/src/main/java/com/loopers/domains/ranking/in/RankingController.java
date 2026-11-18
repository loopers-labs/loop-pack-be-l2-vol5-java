package com.loopers.domains.ranking.in;

import com.loopers.common.in.ApiResponse;
import com.loopers.common.in.error.CoreException;
import com.loopers.common.in.error.ErrorType;
import com.loopers.domains.ranking.app.WeeklyRankingQueryService;
import com.loopers.domains.ranking.app.WeeklyRankingResult;
import com.loopers.domains.user.in.CustomerIdentity;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RankingController {
    private final WeeklyRankingQueryService weeklyRankingQueryService;
    private final CustomerIdentity customerIdentity;

    public RankingController(WeeklyRankingQueryService weeklyRankingQueryService, CustomerIdentity customerIdentity) {
        this.weeklyRankingQueryService = weeklyRankingQueryService;
        this.customerIdentity = customerIdentity;
    }

    @GetMapping("/api/v1/rankings/weekly")
    public ApiResponse<WeeklyRankingResult> weekly(@RequestHeader(value = "X-USER-ID", required = false) String user,
        @RequestParam String periodStart, @RequestParam(defaultValue = "10") int limit) {
        customerIdentity.require(user);
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
