package com.loopers.infrastructure.ranking.freeze;

import com.loopers.domain.ranking.SnapshotInputLine;
import com.loopers.domain.ranking.WeeklyPeriod;
import com.loopers.domain.ranking.freeze.WeeklyRankingSnapshotInput;
import com.loopers.domain.ranking.freeze.WeeklyRankingSnapshotInputRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

@RequiredArgsConstructor
@Component
public class WeeklyRankingSnapshotInputRepositoryImpl implements WeeklyRankingSnapshotInputRepository {
    private final WeeklyRankingSnapshotInputJpaRepository jpaRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public int copyPurchases(String snapshotId, WeeklyPeriod period, ZonedDateTime copiedAt) {
        return entityManager.createQuery("""
                insert into WeeklyRankingSnapshotInput
                    (snapshotId, orderId, productId, quantity, confirmedAt, createdAt, updatedAt)
                select :snapshotId, a.orderId, a.productId, a.quantity, a.confirmedAt, :copiedAt, :copiedAt
                from PurchaseActivity a
                where a.confirmedAt >= :start and a.confirmedAt < :end
                """)
            .setParameter("snapshotId", snapshotId)
            .setParameter("copiedAt", copiedAt)
            .setParameter("start", period.startInclusive().atZone(ZoneOffset.UTC))
            .setParameter("end", period.endExclusive().atZone(ZoneOffset.UTC))
            .executeUpdate();
    }

    @Override
    public List<SnapshotInputLine> findLines(String snapshotId) {
        return jpaRepository.findBySnapshotIdOrderByOrderIdAscProductIdAsc(snapshotId).stream()
            .map(WeeklyRankingSnapshotInput::toLine)
            .toList();
    }

    @Override
    public long sumQuantity(String snapshotId, Long productId) {
        return jpaRepository.sumQuantity(snapshotId, productId);
    }
}
