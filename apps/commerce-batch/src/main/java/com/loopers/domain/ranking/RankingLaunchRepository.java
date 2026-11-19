package com.loopers.domain.ranking;

import java.time.LocalDate;
import java.util.OptionalLong;

public interface RankingLaunchRepository {

    /** 기간의 snapshot 최대 revision. 삭제된 snapshot 도 사용한 번호로 센다. */
    OptionalLong findMaxSnapshotRevision(LocalDate periodStart);
}
