package com.loopers.domains.ranking.app.port;

import java.time.LocalDate;
import java.util.Optional;

public interface WeeklyRankingReader {
    // 공개본이 없으면 비어 있다. 공개본이 있으면 삭제 상품을 거른 뒤 limit개까지 담는다.
    Optional<PublishedRanking> findPublished(LocalDate periodStart, int limit);
}
