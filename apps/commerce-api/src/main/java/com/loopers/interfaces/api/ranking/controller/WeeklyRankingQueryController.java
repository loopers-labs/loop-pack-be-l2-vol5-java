package com.loopers.interfaces.api.ranking.controller;

import com.loopers.application.ranking.query.WeeklyRankingCriteria;
import com.loopers.application.ranking.query.WeeklyRankingQueryDao;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.ranking.dto.WeeklyRankingResponse;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/rankings")
@RequiredArgsConstructor
public class WeeklyRankingQueryController {
    private static final Pattern PERIOD_START_FORMAT = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");
    private static final int DEFAULT_LIMIT = 10;
    private final WeeklyRankingQueryDao weeklyRankingQueryDao;

    @GetMapping("/weekly")
    public ApiResponse<WeeklyRankingResponse> findWeekly(
        @RequestParam String periodStart,
        @RequestParam(required = false) String limit
    ) {
        var criteria = toCriteria(periodStart, limit);
        var response = weeklyRankingQueryDao.findPublished(criteria)
            .map(ranking -> WeeklyRankingResponse.published(criteria, ranking))
            .orElseGet(() -> WeeklyRankingResponse.notPublished(criteria));
        return ApiResponse.success(response);
    }

    private WeeklyRankingCriteria toCriteria(String periodStart, String limit) {
        if (!PERIOD_START_FORMAT.matcher(periodStart).matches()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "periodStart는 YYYY-MM-DD 형식이어야 합니다.");
        }
        try {
            var startDate = LocalDate.parse(periodStart);
            int maximumItems = limit == null ? DEFAULT_LIMIT : Integer.parseInt(limit);
            return new WeeklyRankingCriteria(startDate, maximumItems);
        } catch (DateTimeParseException invalidDate) {
            throw new CoreException(ErrorType.BAD_REQUEST, "periodStart는 유효한 날짜여야 합니다.");
        } catch (NumberFormatException invalidLimit) {
            throw new CoreException(ErrorType.BAD_REQUEST, "limit은 1 이상 100 이하의 정수여야 합니다.");
        } catch (IllegalArgumentException invalidCriteria) {
            throw new CoreException(ErrorType.BAD_REQUEST, invalidCriteria.getMessage());
        }
    }
}
