package com.loopers.batch.job.ranking;

import com.loopers.batch.job.ranking.WeeklyRankingSnapshotRepository.SnapshotManifest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * 계산을 마친 snapshot 에 순위를 채우고 그 기간의 공개본으로 바꾼다. 호출하는 TX 하나에서 실행되어야 한다.
 * 도중에 실패하면 순위 · 기존 공개본 내리기가 함께 rollback 되어 이전 공개본이 그대로 보인다 (9주차 D-180, D-181)
 */
@RequiredArgsConstructor
@Component
public class WeeklyRankingPublisher {

    private final WeeklyRankingSnapshotRepository snapshots;
    private final Clock clock;

    public PublishResult publish(WeeklyRankingParameters params) {
        SnapshotManifest manifest = snapshots.find(params.snapshotId())
            .orElseThrow(() -> new IllegalStateException("고정된 입력이 없음. snapshotId=" + params.snapshotId()));
        // 공개 commit 뒤 실행 기록만 실패해 다시 온 경우. 같은 결과로 수렴한다
        if (manifest.is("PUBLISHED")) {
            return PublishResult.ALREADY_PUBLISHED;
        }
        if (!manifest.is("COMPLETED")) {
            throw new IllegalStateException("계산을 마친 snapshot 만 공개함. manifest=" + manifest);
        }
        Optional<Integer> publishedRevision = snapshots.findPublishedRevision(params.periodStart());
        if (publishedRevision.isPresent() && publishedRevision.get() > params.revision()) {
            throw new IllegalStateException("더 새 revision 이 이미 공개됨. published=r" + publishedRevision.get() + " manifest=" + manifest);
        }

        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
        snapshots.assignRanks(params.snapshotId(), now);
        snapshots.demotePublished(params.periodStart(), params.revision(), now);
        if (snapshots.promote(params.snapshotId(), now) != 1) {
            throw new IllegalStateException("공개 전환 실패. manifest=" + manifest);
        }
        return PublishResult.PUBLISHED;
    }

    public enum PublishResult {
        PUBLISHED,
        ALREADY_PUBLISHED
    }
}
