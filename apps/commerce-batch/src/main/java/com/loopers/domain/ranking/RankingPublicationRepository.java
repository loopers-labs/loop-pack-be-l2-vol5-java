package com.loopers.domain.ranking;

import java.math.BigInteger;
import java.time.LocalDate;
import java.util.Optional;

public interface RankingPublicationRepository {

    Optional<RankingInputManifest> findSnapshot(String snapshotId);

    /** forUpdate 이면 FOR UPDATE current read 로 읽는다. */
    Optional<CurrentPublication> findCurrentPublication(LocalDate periodStart, boolean forUpdate);

    long countResults(String snapshotId);

    PreparedCounts countPreparedProducts(String snapshotId);

    /** stage 에서 score DESC, productId ASC 의 ROW_NUMBER 순위로 완성 결과를 저장하고 행 수를 반환한다. */
    int insertRankedResults(String snapshotId);

    /** calculated_rank 오름차순으로 완성 결과를 읽는다. */
    void forEachRankedResult(String snapshotId, RankedResultHandler handler);

    int markCompleted(String snapshotId, LocalDate periodStart, long revision, String resultChecksum);

    /** 같은 기간의 정상 완성본보다 대상 revision 이 높을 때만 pointer 를 바꾼다. */
    int publishIfNewer(String snapshotId, LocalDate periodStart);

    int insertPublication(LocalDate periodStart, String snapshotId);

    record PreparedCounts(long stageCount, long preparedProductCount) {}

    @FunctionalInterface
    interface RankedResultHandler {
        void accept(long productId, BigInteger score, long rank);
    }
}
