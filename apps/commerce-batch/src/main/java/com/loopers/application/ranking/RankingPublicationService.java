package com.loopers.application.ranking;

import com.loopers.domain.ranking.CurrentPublication;
import com.loopers.domain.ranking.PublicationOutcome;
import com.loopers.domain.ranking.PublicationResult;
import com.loopers.domain.ranking.RankingChecksum;
import com.loopers.domain.ranking.RankingInputManifest;
import com.loopers.domain.ranking.RankingPublicationException;
import com.loopers.domain.ranking.RankingPublicationException.Reason;
import com.loopers.domain.ranking.RankingPublicationRepository;
import com.loopers.domain.ranking.RankingPublicationRepository.PreparedCounts;
import com.loopers.domain.ranking.SnapshotStatus;
import com.loopers.domain.ranking.WeeklyRankingRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/** 완성 확인·순위·COMPLETED 와 공개 pointer 판정을 호출자의 한 Step TX 에서 처리한다(랭킹 설계 6.5~6.8). */
@Service
@RequiredArgsConstructor
public class RankingPublicationService {

    private final RankingPublicationRepository repository;

    @Transactional(propagation = Propagation.MANDATORY)
    public PublicationResult publish(WeeklyRankingRequest request) {
        RankingInputManifest target = repository.findSnapshot(request.snapshotId())
            .orElseThrow(() -> fail(Reason.SNAPSHOT_NOT_READY, "공개할 snapshot 이 없습니다."));
        if (!target.matches(request)) {
            throw fail(Reason.SNAPSHOT_CONTRACT_MISMATCH, "snapshot 이 요청과 다릅니다.");
        }
        if (target.deleted() || target.inputFixedAt() == null || target.inputChecksum() == null) {
            throw fail(Reason.SNAPSHOT_NOT_READY, "snapshot manifest 가 불완전합니다.");
        }
        repository.findCurrentPublication(request.periodStart(), false).ifPresent(RankingPublicationService::requireValid);

        if (target.status() == SnapshotStatus.READY) {
            complete(target);
        } else if (target.completedAt() == null || target.resultChecksum() == null
            || repository.countResults(target.snapshotId()) != target.productCount()) {
            throw fail(Reason.INCONSISTENT_RESULT, "완료 snapshot 의 결과가 manifest 와 다릅니다.");
        }
        return publishPointer(target);
    }

    private void complete(RankingInputManifest target) {
        String snapshotId = target.snapshotId();
        if (repository.countResults(snapshotId) != 0) {
            throw fail(Reason.INCONSISTENT_RESULT, "READY snapshot 에 완성 결과가 이미 있습니다.");
        }
        PreparedCounts counts = repository.countPreparedProducts(snapshotId);
        if (counts.stageCount() != counts.preparedProductCount() || counts.preparedProductCount() != target.productCount()) {
            throw fail(Reason.INCOMPLETE_SCORES, "모든 대상 상품의 점수가 준비되지 않았습니다.");
        }
        if (repository.insertRankedResults(snapshotId) != target.productCount()) {
            throw fail(Reason.UNEXPECTED_WRITE_COUNT, "완성 결과 저장 건수가 상품 수와 다릅니다.");
        }
        RankingChecksum checksum = new RankingChecksum();
        repository.forEachRankedResult(snapshotId, checksum::addResultRow);
        if (checksum.rowCount() != target.productCount()) {
            throw fail(Reason.INCONSISTENT_RESULT, "결과 checksum 대상 행 수가 상품 수와 다릅니다.");
        }
        if (repository.markCompleted(snapshotId, target.periodStart(), target.revision(), checksum.finishHex()) != 1) {
            throw fail(Reason.UNEXPECTED_WRITE_COUNT, "COMPLETED 전이 영향 행 수가 1이 아닙니다.");
        }
    }

    private PublicationResult publishPointer(RankingInputManifest target) {
        String targetId = target.snapshotId();
        int changed = repository.publishIfNewer(targetId, target.periodStart());
        if (changed == 1) {
            return new PublicationResult(targetId, targetId, PublicationOutcome.PUBLISHED);
        }
        if (changed != 0) {
            throw fail(Reason.UNEXPECTED_WRITE_COUNT, "공개 pointer 갱신 행 수가 비정상입니다.");
        }
        Optional<CurrentPublication> current = repository.findCurrentPublication(target.periodStart(), true);
        if (current.isEmpty()) {
            if (repository.insertPublication(target.periodStart(), targetId) != 1) {
                throw fail(Reason.UNEXPECTED_WRITE_COUNT, "공개 pointer INSERT 영향 행 수가 1이 아닙니다.");
            }
            return new PublicationResult(targetId, targetId, PublicationOutcome.PUBLISHED);
        }
        CurrentPublication publication = requireValid(current.get());
        if (publication.snapshotId().equals(targetId)) {
            return new PublicationResult(targetId, targetId, PublicationOutcome.ALREADY_PUBLISHED);
        }
        if (publication.revision() > target.revision()) {
            return new PublicationResult(targetId, publication.snapshotId(), PublicationOutcome.SKIPPED_NEWER);
        }
        throw fail(Reason.INVALID_PUBLICATION, "공개 pointer 상태가 갱신 조건과 맞지 않습니다.");
    }

    private static CurrentPublication requireValid(CurrentPublication publication) {
        if (!publication.pointsToCompletedSnapshot()) {
            throw fail(Reason.INVALID_PUBLICATION, "공개 pointer 가 같은 기간의 완료 snapshot 을 가리키지 않습니다.");
        }
        return publication;
    }

    private static RankingPublicationException fail(Reason reason, String message) {
        return new RankingPublicationException(reason, message);
    }
}
