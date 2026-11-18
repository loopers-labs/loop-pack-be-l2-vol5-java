package com.loopers.domains.ranking.in;

import com.loopers.common.in.ApiResponse;
import com.loopers.domains.ranking.app.WeeklyRankingQueryService;
import com.loopers.domains.ranking.app.WeeklyRankingResult;
import com.loopers.domains.user.in.CustomerIdentity;
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
        return ApiResponse.success(null);
    }
}
