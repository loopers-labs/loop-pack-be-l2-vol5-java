package com.loopers.interfaces.api.ranking;

import com.loopers.application.ranking.WeeklyRankingInfo;
import com.loopers.application.ranking.WeeklyRankingQueryService;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.commerce.InvalidRequestException;
import com.loopers.interfaces.api.commerce.StrictInput;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

@RestController
@RequestMapping(value = "/api/v1/rankings/weekly", produces = "application/json")
public class WeeklyRankingController {
    private final WeeklyRankingQueryService queries;

    public WeeklyRankingController(WeeklyRankingQueryService queries) {
        this.queries = queries;
    }

    @GetMapping
    public ApiResponse<WeeklyRankingInfo> get(@RequestParam(required = false) String periodStart,
                                            @RequestParam(required = false) String limit) {
        LocalDate week = week(periodStart);
        int requestedLimit = limit == null ? 10 : StrictInput.size(limit);
        return ApiResponse.success(queries.query(week, requestedLimit));
    }

    private LocalDate week(String value) {
        if (value == null || !value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
            throw new InvalidRequestException();
        }
        try {
            LocalDate parsed = LocalDate.parse(value);
            if (parsed.getDayOfWeek() != DayOfWeek.MONDAY || parsed.getYear() < 1 || parsed.plusDays(7).getYear() > 9999) {
                throw new InvalidRequestException();
            }
            return parsed;
        } catch (DateTimeParseException exception) {
            throw new InvalidRequestException();
        }
    }
}
