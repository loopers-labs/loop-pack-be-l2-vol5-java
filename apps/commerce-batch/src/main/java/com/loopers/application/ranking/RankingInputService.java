package com.loopers.application.ranking;

import com.loopers.domain.ranking.RankingChecksum;
import com.loopers.domain.ranking.RankingInputException;
import com.loopers.domain.ranking.RankingInputException.Reason;
import com.loopers.domain.ranking.RankingInputManifest;
import com.loopers.domain.ranking.RankingInputRepository;
import com.loopers.domain.ranking.RankingInputRepository.InputCounts;
import com.loopers.domain.ranking.SnapshotStatus;
import com.loopers.domain.ranking.WeeklyRankingRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/** 입력 고정(랭킹 설계 8.2). 호출자의 Step TX 에 참여하며 실패하면 복사·manifest 가 함께 rollback 된다. */
@Service
@RequiredArgsConstructor
public class RankingInputService {

    private final RankingInputRepository repository;

    @Transactional(propagation = Propagation.MANDATORY)
    public RankingInputManifest freezeOrReuseInput(WeeklyRankingRequest request) {
        Optional<RankingInputManifest> byId = repository.findSnapshot(request.snapshotId());
        Optional<RankingInputManifest> byPeriod =
            repository.findSnapshotByPeriodAndRevision(request.periodStart(), request.revision());
        if (byId.isPresent() != byPeriod.isPresent()
            || byId.isPresent() && !byId.get().snapshotId().equals(byPeriod.get().snapshotId())) {
            throw new RankingInputException(Reason.SNAPSHOT_CONTRACT_MISMATCH, "snapshotId 와 기간·revision 이 다른 snapshot 을 가리킵니다.");
        }
        return byId.isPresent() ? reuse(request, byId.get()) : freeze(request);
    }

    private RankingInputManifest reuse(WeeklyRankingRequest request, RankingInputManifest existing) {
        if (!existing.matches(request)) {
            throw new RankingInputException(Reason.SNAPSHOT_CONTRACT_MISMATCH, "기존 snapshot 의 기간·revision 이 요청과 다릅니다.");
        }
        boolean completedFieldsValid = existing.status() != SnapshotStatus.COMPLETED
            || existing.completedAt() != null && existing.resultChecksum() != null;
        if (existing.deleted() || existing.inputFixedAt() == null || existing.inputChecksum() == null
            || existing.inputCount() < 0 || existing.productCount() > existing.inputCount() || !completedFieldsValid) {
            throw inconsistent("기존 snapshot manifest 가 불완전합니다.");
        }
        InputCounts counts = repository.countInput(existing.snapshotId());
        RankingChecksum checksum = checksumOf(existing.snapshotId());
        if (counts.inputCount() != existing.inputCount() || counts.productCount() != existing.productCount()
            || checksum.rowCount() != existing.inputCount() || !checksum.finishHex().equals(existing.inputChecksum())) {
            throw inconsistent("저장된 입력이 manifest 와 다릅니다.");
        }
        return existing;
    }

    private RankingInputManifest freeze(WeeklyRankingRequest request) {
        if (repository.hasOrphanData(request.snapshotId())) {
            throw inconsistent("snapshot 정보 없이 남은 입력·점수·결과·공개가 있습니다.");
        }
        repository.copyInput(request);
        LocalDateTime inputFixedAt = repository.readDatabaseUtcNow();
        InputCounts counts = repository.countInput(request.snapshotId());
        RankingChecksum checksum = checksumOf(request.snapshotId());
        if (checksum.rowCount() != counts.inputCount()) {
            throw inconsistent("checksum 대상 행 수가 입력 건수와 다릅니다.");
        }
        RankingInputManifest manifest = RankingInputManifest.ready(
            request, inputFixedAt, counts.inputCount(), counts.productCount(), checksum.finishHex());
        repository.insertReadySnapshot(manifest);
        return manifest;
    }

    private RankingChecksum checksumOf(String snapshotId) {
        RankingChecksum checksum = new RankingChecksum();
        repository.forEachInputOrdered(snapshotId, checksum::addInputRow);
        return checksum;
    }

    private static RankingInputException inconsistent(String message) {
        return new RankingInputException(Reason.INCONSISTENT_INPUT, message);
    }
}
