package com.loopers.domain.ranking;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

public interface RankingInputRepository {

    Optional<RankingInputManifest> findSnapshot(String snapshotId);

    Optional<RankingInputManifest> findSnapshotByPeriodAndRevision(LocalDate periodStart, long revision);

    /** metadata 없이 이 ID 의 input/stage/result 또는 공개 pointer 가 남아 있는지. */
    boolean hasOrphanData(String snapshotId);

    /** 해당 주의 구매 활동을 단일 INSERT SELECT 로 복사한다. */
    void copyInput(WeeklyRankingRequest request);

    LocalDateTime readDatabaseUtcNow();

    InputCounts countInput(String snapshotId);

    /** orderId·productId 오름차순으로 복사 입력을 읽는다. */
    void forEachInputOrdered(String snapshotId, InputRowHandler handler);

    void insertReadySnapshot(RankingInputManifest manifest);

    record InputCounts(long inputCount, long productCount) {}

    @FunctionalInterface
    interface InputRowHandler {
        void accept(long orderId, long productId, long quantity, LocalDateTime confirmedAtUtc);
    }
}
