package com.loopers.domain.ranking;

import java.time.LocalDate;
import java.util.Optional;

public interface WeeklyRankingPublicationRepository {

    /** 같은 기간의 고정·공개를 직렬로 실행하기 위해 기간별 공개 행을 쓰기 잠금으로 읽는다. */
    Optional<WeeklyRankingPublication> findForUpdate(LocalDate periodStart);

    WeeklyRankingPublication saveAndFlush(WeeklyRankingPublication publication);
}
