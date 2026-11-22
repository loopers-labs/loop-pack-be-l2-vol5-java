package com.loopers.batch.job.ranking;

import com.loopers.batch.job.ranking.WeeklyRankingStore.SnapshotManifest;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.Optional;

/**
 * 늦은 구매 감지(ADR 0079). 현재 UTC 주의 바로 앞 주 하나만 보고, 매시 무엇을 실행할지 정한다.
 * <ol>
 *   <li>그 주의 snapshot이 없으면 아무것도 하지 않는다(r1은 정기 실행의 몫).</li>
 *   <li>마지막 revision이 FROZEN(실패한 채 남음)이면 같은 snapshotId로 재시작한다.</li>
 *   <li>ABANDONED면 아무것도 하지 않는다(운영자가 원인을 고치고 다음 revision을 실행, ADR 0083).</li>
 *   <li>COMPLETED면 원본 건수와 snapshot 입력 건수를 비교한다. 같으면 아무것도 하지 않는다.</li>
 *   <li>다르더라도 그 주에 최근 30분 안에 저장된 행이 있으면 이번에는 넘긴다(나눠 들어오는 늦은 구매).</li>
 *   <li>그렇지 않으면 다음 revision을 실행한다.</li>
 * </ol>
 * created_at은 입력의 정확성이 아니라 "조용해졌는지"를 보는 데만 쓴다. 입력은 건수 비교와 snapshot이 정한다.
 */
public class LateArrivalDetector {
    static final Duration QUIET_PERIOD = Duration.ofMinutes(30);

    private final WeeklyRankingStore store;
    private final Clock clock;

    public LateArrivalDetector(WeeklyRankingStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    public enum Action { NONE, RESTART, NEW_REVISION }

    /** 할 일과 대상 snapshotId(NONE이면 비어 있음), 판단 이유. */
    public record Decision(Action action, LocalDate periodStart, String snapshotId, String reason) {
        static Decision none(LocalDate periodStart, String reason) {
            return new Decision(Action.NONE, periodStart, null, reason);
        }
    }

    public Decision decide() {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDate periodStart = now.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(1);

        Optional<SnapshotManifest> latest = store.findLatestSnapshot(periodStart);
        if (latest.isEmpty()) {
            return Decision.none(periodStart, "그 주의 snapshot이 없음(r1은 정기 실행이 만든다)");
        }
        SnapshotManifest manifest = latest.get();
        if (WeeklyRankingStore.STATUS_FROZEN.equals(manifest.status())) {
            return new Decision(Action.RESTART, periodStart, manifest.snapshotId(), "마지막 revision이 끝나지 않음 → 재시작");
        }
        if (WeeklyRankingStore.STATUS_ABANDONED.equals(manifest.status())) {
            return Decision.none(periodStart, "마지막 revision이 ABANDONED → 운영자가 처리");
        }

        WeeklySnapshotId current = new WeeklySnapshotId(periodStart, manifest.revision());
        int sourceRows = store.sourceRows(current);
        if (sourceRows == manifest.inputCount()) {
            return Decision.none(periodStart, "원본 " + sourceRows + "건 = 입력 " + manifest.inputCount() + "건");
        }
        Optional<LocalDateTime> lastStoredAt = store.lastStoredAt(current);
        if (lastStoredAt.isPresent() && lastStoredAt.get().isAfter(now.minus(QUIET_PERIOD))) {
            return Decision.none(periodStart, "원본 " + sourceRows + "건 ≠ 입력 " + manifest.inputCount()
                + "건이지만 최근 30분 안에 저장된 행이 있어 다음 확인으로 미룸");
        }
        WeeklySnapshotId next = new WeeklySnapshotId(periodStart, manifest.revision() + 1);
        return new Decision(Action.NEW_REVISION, periodStart, next.value(),
            "원본 " + sourceRows + "건 ≠ 입력 " + manifest.inputCount() + "건 → " + next.value());
    }
}
