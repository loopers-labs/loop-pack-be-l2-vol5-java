package com.loopers.domain.ranking;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * commerce-batch가 공개한 주간 랭킹을 읽는다. 테이블은 commerce-batch가 소유하며 여기서는 쓰지 않는다(ADR 0080).
 * 같은 읽기 TX 안에서 부르면 pointer와 완성 랭킹이 같은 시점의 값이다.
 */
public interface WeeklyRankingReader {

    /** 기간의 공개 pointer가 가리키는 완성본 정보. 공개본이 없으면 비어 있다. */
    Optional<PublishedSnapshot> findPublished(LocalDate periodStart);

    /** 그 snapshot의 완성 랭킹을 계산 순위 순서로 offset부터 limit개 읽는다. */
    List<Entry> findEntries(String snapshotId, int offset, int limit);

    record PublishedSnapshot(String snapshotId, int revision, Instant inputFrozenAt) {}

    record Entry(long productId, long score, int rank) {}
}
