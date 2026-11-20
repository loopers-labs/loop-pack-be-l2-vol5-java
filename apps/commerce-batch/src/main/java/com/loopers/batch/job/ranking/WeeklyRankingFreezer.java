package com.loopers.batch.job.ranking;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * 입력 고정 (설계 10-4). 호출한 tasklet 의 TX 하나에서 snapshot 입력 복사와 snapshot 정보(manifest)를 함께 commit 한다.
 * 실패하면 둘 다 rollback 되어 반쪽 snapshot 이 남지 않는다.
 */
@RequiredArgsConstructor
@Component
public class WeeklyRankingFreezer {
    private final WeeklyRankingStore store;
    private final Clock clock;

    public WeeklyRankingStore.Snapshot freeze(WeeklyRankingTarget target, long jobExecutionId) {
        Optional<WeeklyRankingStore.Snapshot> existing = store.findSnapshotForUpdate(target.snapshotId());
        if (existing.isPresent()) {
            return verifyFrozen(target, existing.get());
        }

        Instant now = clock.instant();
        if (now.isBefore(target.endInstant())) {
            throw new WeeklyRankingException("기간이 아직 끝나지 않았습니다. [snapshotId = " + target.snapshotId()
                + ", periodEnd = " + target.endInstant() + ", now = " + now + "]");
        }

        Optional<WeeklyRankingStore.Snapshot> latest = store.findLatestOfPeriodForUpdate(target.periodStart());
        long expected = latest.map(snapshot -> snapshot.revision() + 1).orElse(1L);
        if (target.revision() != expected) {
            throw new WeeklyRankingException("revision 은 기간의 최대 revision + 1 이어야 합니다. [snapshotId = " + target.snapshotId()
                + ", expected = " + expected + "]");
        }
        if (latest.isPresent() && latest.get().status() == SnapshotStatus.FROZEN) {
            throw new WeeklyRankingException("이전 revision 이 아직 공개 단계를 마치지 않았습니다. 그 실행을 먼저 재시작해 끝냅니다. [previous = "
                + latest.get().snapshotId() + ", requested = " + target.snapshotId() + "]");
        }

        int copied = store.copyInputs(target.snapshotId(), target.startUtc(), target.endUtc());
        List<RankingChecksum.InputLine> inputs = store.readInputs(target.snapshotId());
        if (copied != inputs.size()) {
            throw new IllegalStateException("복사한 입력 수와 읽은 입력 수가 다릅니다. [copied = " + copied + ", read = " + inputs.size() + "]");
        }
        int productCount = (int) inputs.stream().mapToLong(RankingChecksum.InputLine::productId).distinct().count();
        WeeklyRankingStore.Snapshot snapshot = new WeeklyRankingStore.Snapshot(
            target.snapshotId(), target.periodStart(), target.periodEnd(), target.revision(), SnapshotStatus.FROZEN,
            LocalDateTime.ofInstant(now, ZoneOffset.UTC), inputs.size(), productCount, RankingChecksum.ofInputs(inputs), null);
        store.insertSnapshot(snapshot, jobExecutionId);
        return snapshot;
    }

    /**
     * 이미 고정된 snapshot (고정 TX commit 뒤 step 의 최종 상태 저장 전에 멈춘 재실행). 다시 복사하지 않고, 기간·revision·입력이 같은지만 본다.
     * 다르면 덮어쓰지 않고 충돌로 실패한다.
     */
    private WeeklyRankingStore.Snapshot verifyFrozen(WeeklyRankingTarget target, WeeklyRankingStore.Snapshot existing) {
        List<RankingChecksum.InputLine> inputs = store.readInputs(existing.snapshotId());
        String checksum = RankingChecksum.ofInputs(inputs);
        if (!existing.periodStart().equals(target.periodStart()) || !existing.periodEnd().equals(target.periodEnd())
            || existing.revision() != target.revision() || existing.inputCount() != inputs.size()
            || !existing.inputChecksum().equals(checksum)) {
            throw new WeeklyRankingException("이미 있는 snapshot 과 기간·revision·입력이 다릅니다. 덮어쓰지 않습니다. [snapshotId = "
                + existing.snapshotId() + ", stored = " + existing.periodStart() + "/r" + existing.revision() + "/" + existing.inputCount()
                + "/" + existing.inputChecksum() + ", actual = " + target.periodStart() + "/r" + target.revision() + "/" + inputs.size()
                + "/" + checksum + "]");
        }
        return existing;
    }
}
