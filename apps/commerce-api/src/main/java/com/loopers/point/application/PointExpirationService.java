package com.loopers.point.application;

import com.loopers.point.application.port.in.PointExpirationUseCase;
import com.loopers.point.application.port.out.PointGroupPort;
import com.loopers.point.application.port.out.PointHistoryPort;
import com.loopers.point.domain.PointGroup;
import com.loopers.point.domain.PointHistory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 포인트 만료 처리 (PNT-08, ADR-10, 8-1).
 * 대상 그룹을 묶음 크기만큼 읽어 만료 처리와 만료 이력 저장을 하고, 묶음마다 커밋한다.
 * 그래서 이 메서드 전체가 아니라 묶음 하나가 트랜잭션 하나다 (ADR-11의 예외). 중간에 실패해도 커밋된 묶음은 남고,
 * 다시 실행하면 남은 대상만 처리한다.
 */
@RequiredArgsConstructor
@Component
public class PointExpirationService implements PointExpirationUseCase {

    static final int CHUNK_SIZE = 1_000;

    private final PointGroupPort pointGroupPort;
    private final PointHistoryPort pointHistoryPort;
    private final TransactionTemplate transactionTemplate;

    @Override
    public int expireAll(ZonedDateTime now) {
        return expireAll(now, CHUNK_SIZE);
    }

    /**
     * 묶음이 가득 찼을 때만 다음 묶음으로 간다. 묶음 크기는 실제로 금액을 만료한 그룹 수로 센다.
     * 조회 조건이 잘못되어 이미 처리한 그룹이 다시 나와도, 그 묶음은 0으로 세어져 끝없이 돌지 않는다.
     */
    int expireAll(ZonedDateTime now, int chunkSize) {
        int total = 0;
        int expired;
        do {
            expired = Objects.requireNonNull(transactionTemplate.execute(status -> expireChunk(now, chunkSize)));
            total += expired;
        } while (expired == chunkSize);
        return total;
    }

    /**
     * 0원 만료 이력은 남기지 않는다 (P-21과 같은 이유 — 0원 이력은 원인을 찾기 어려운 데이터다).
     */
    private int expireChunk(ZonedDateTime now, int chunkSize) {
        List<PointGroup> groups = pointGroupPort.findExpirableForUpdate(now, chunkSize);
        List<PointHistory> histories = groups.stream()
            .map(group -> PointHistory.expire(group, group.expire(now), now))
            .filter(history -> history.getAmount() != 0)
            .toList();
        pointHistoryPort.saveAll(histories);
        return histories.size();
    }
}
