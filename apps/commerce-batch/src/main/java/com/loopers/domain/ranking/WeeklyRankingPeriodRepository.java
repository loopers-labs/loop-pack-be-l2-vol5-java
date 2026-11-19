package com.loopers.domain.ranking;

import java.time.LocalDate;
import java.util.Optional;

public interface WeeklyRankingPeriodRepository {

    /** 같은 기간의 고정·공개를 직렬로 실행하기 위해 기간 행을 쓰기 잠금으로 읽는다. */
    Optional<WeeklyRankingPeriod> findForUpdate(LocalDate periodStart);

    WeeklyRankingPeriod saveAndFlush(WeeklyRankingPeriod period);
}
