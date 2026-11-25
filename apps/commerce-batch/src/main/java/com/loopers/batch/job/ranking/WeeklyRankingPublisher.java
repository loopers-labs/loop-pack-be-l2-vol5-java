package com.loopers.batch.job.ranking;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.Clock;
import java.util.List;
import java.util.Optional;

/**
 * 완성본 공개 (설계 10-5). 호출한 tasklet 의 TX 하나에서 완성 조건 확인 → 순위 생성 → pointer 조건부 갱신 → snapshot 상태 변경을 한다.
 * 중간에 실패하면 전부 rollback 되어 새 pointer 나 일부 ranking 이 보이지 않는다.
 * 이미 공개 단계를 마친 snapshot 이면 아무것도 바꾸지 않는다 (공개 TX commit 뒤 Batch metadata 저장 전에 멈춘 재실행).
 */
@RequiredArgsConstructor
@Component
public class WeeklyRankingPublisher {
    private final WeeklyRankingStore store;
    private final Clock clock;

    public SnapshotStatus publish(WeeklyRankingTarget target, long jobExecutionId) {
        String snapshotId = target.snapshotId();
        WeeklyRankingStore.Snapshot snapshot = store.findSnapshotForUpdate(snapshotId)
            .orElseThrow(() -> new WeeklyRankingException("고정된 snapshot 이 없습니다. [snapshotId = " + snapshotId + "]"));
        if (snapshot.status() != SnapshotStatus.FROZEN) {
            verifyFinished(snapshot);
            return snapshot.status();
        }

        int staged = store.countStage(snapshotId);
        long stagedSum = store.sumStage(snapshotId);
        long inputSum = store.sumInput(snapshotId);
        if (staged != snapshot.productCount() || stagedSum != inputSum) {
            throw new WeeklyRankingException("점수가 완성되지 않았습니다. [snapshotId = " + snapshotId + ", products = " + staged + "/"
                + snapshot.productCount() + ", score sum = " + stagedSum + "/" + inputSum + "]");
        }
        int ranked = store.insertRanking(snapshotId);
        if (ranked != staged) {
            throw new IllegalStateException("순위 행 수가 점수 행 수와 다릅니다. [snapshotId = " + snapshotId + ", ranked = " + ranked + ", staged = " + staged + "]");
        }
        String resultChecksum = RankingChecksum.ofResults(store.readRanking(snapshotId));
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);

        SnapshotStatus status = movePointer(snapshot, now);
        if (store.markPublished(snapshotId, status, resultChecksum, now, jobExecutionId) != 1) {
            throw new IllegalStateException("snapshot 상태를 바꾸지 못했습니다. [snapshotId = " + snapshotId + "]");
        }
        return status;
    }

    /** pointer 를 잠그고, 없으면 만들고, 더 오래된 revision 이면 바꾼다. 더 새 revision 이 공개돼 있으면 그대로 두고 SUPERSEDED. */
    private SnapshotStatus movePointer(WeeklyRankingStore.Snapshot snapshot, LocalDateTime now) {
        Optional<WeeklyRankingStore.Publication> current = store.findPublicationForUpdate(snapshot.periodStart());
        if (current.isEmpty()) {
            store.insertPublication(snapshot.periodStart(), snapshot.snapshotId(), snapshot.revision(), now);
            return SnapshotStatus.PUBLISHED;
        }
        long publishedRevision = current.get().revision();
        if (publishedRevision < snapshot.revision()) {
            if (store.replacePublicationIfOlder(snapshot.periodStart(), snapshot.snapshotId(), snapshot.revision(), now) != 1) {
                throw new IllegalStateException("pointer 를 바꾸지 못했습니다. [periodStart = " + snapshot.periodStart() + "]");
            }
            return SnapshotStatus.PUBLISHED;
        }
        if (publishedRevision == snapshot.revision()) {
            throw new IllegalStateException("FROZEN snapshot 의 revision 이 이미 공개돼 있습니다. [pointer = " + current.get().snapshotId()
                + ", snapshotId = " + snapshot.snapshotId() + "]");
        }
        return SnapshotStatus.SUPERSEDED;
    }

    /** 공개 단계를 마친 snapshot: 완성 ranking 이 저장된 결과 checksum 과 같은지만 확인한다. 지우거나 다시 만들지 않는다. */
    private void verifyFinished(WeeklyRankingStore.Snapshot snapshot) {
        List<RankingChecksum.ResultLine> ranking = store.readRanking(snapshot.snapshotId());
        if (ranking.size() != snapshot.productCount() || !RankingChecksum.ofResults(ranking).equals(snapshot.resultChecksum())) {
            throw new IllegalStateException("공개된 snapshot 의 ranking 이 기록과 다릅니다. [snapshotId = " + snapshot.snapshotId() + "]");
        }
    }
}
