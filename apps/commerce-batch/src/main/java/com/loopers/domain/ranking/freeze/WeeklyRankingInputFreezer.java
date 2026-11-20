package com.loopers.domain.ranking.freeze;

import com.loopers.domain.ranking.SnapshotChecksum;
import com.loopers.domain.ranking.SnapshotInputLine;
import com.loopers.domain.ranking.WeeklyPeriod;
import com.loopers.domain.ranking.WeeklyRankingPeriod;
import com.loopers.domain.ranking.WeeklyRankingPeriodRepository;
import com.loopers.domain.ranking.WeeklyRankingSnapshot;
import com.loopers.domain.ranking.WeeklyRankingSnapshotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 기간의 구매 활동을 snapshot 입력으로 고정하고 manifest 를 남긴다.
 * 호출하는 쪽이 READ COMMITTED TX 를 연다. 복사 한 문장이 하나의 읽기 시점이 된다.
 */
@RequiredArgsConstructor
@Component
public class WeeklyRankingInputFreezer {

    private final WeeklyRankingPeriodRepository periodRepository;
    private final WeeklyRankingSnapshotRepository snapshotRepository;
    private final WeeklyRankingSnapshotInputRepository inputRepository;
    private final Clock clock;

    public WeeklyRankingSnapshot freeze(WeeklyPeriod period, int revision, Long jobExecutionId) {
        WeeklyRankingPeriod lockedPeriod = periodRepository.findForUpdate(period.periodStart())
            .orElseGet(() -> periodRepository.saveAndFlush(new WeeklyRankingPeriod(period.periodStart())));

        String snapshotId = period.snapshotId(revision);
        Optional<WeeklyRankingSnapshot> existing = snapshotRepository.findBySnapshotId(snapshotId);
        if (existing.isPresent()) {
            // 이미 고정한 입력은 다시 복사하지 않고, manifest 와 같은지만 확인한다.
            List<SnapshotInputLine> lines = inputRepository.findLines(snapshotId);
            existing.get().verifyInput(lines.size(), SnapshotChecksum.ofInput(lines));
            return existing.get();
        }

        lockedPeriod.assignRevision(revision);
        ZonedDateTime frozenAt = ZonedDateTime.now(clock);
        inputRepository.copyPurchases(snapshotId, period, frozenAt);

        List<SnapshotInputLine> lines = inputRepository.findLines(snapshotId);
        int productCount = (int) lines.stream().map(SnapshotInputLine::productId).distinct().count();
        return snapshotRepository.save(WeeklyRankingSnapshot.freeze(
            period, revision, frozenAt, lines.size(), productCount, SnapshotChecksum.ofInput(lines), jobExecutionId));
    }
}
