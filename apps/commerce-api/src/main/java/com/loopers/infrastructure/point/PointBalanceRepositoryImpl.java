package com.loopers.infrastructure.point;

import com.loopers.domain.point.PointBalanceModel;
import com.loopers.domain.point.PointBalanceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.List;
import org.springframework.data.domain.PageRequest;

@RequiredArgsConstructor
@Component
public class PointBalanceRepositoryImpl implements PointBalanceRepository {

    private final PointBalanceJpaRepository pointBalanceJpaRepository;

    @Override
    public List<Long> findUserIdsAfter(Long afterUserId, int limit) {
        return pointBalanceJpaRepository.findUserIdsAfter(afterUserId, PageRequest.of(0, limit));
    }

    @Override
    public PointBalanceModel save(PointBalanceModel pointBalance) {
        return pointBalanceJpaRepository.save(pointBalance);
    }

    @Override
    public Optional<PointBalanceModel> findByUserIdForUpdate(Long userId) {
        // Lock the root first: every point writer uses the same per-user boundary.
        Optional<PointBalanceModel> balance = pointBalanceJpaRepository.findRootForUpdate(userId);
        if (balance.isPresent()) {
            // Locking fetches avoid an older REPEATABLE READ snapshot for lazy collections.
            // Fetch one collection at a time to avoid Hibernate's multiple-bag fetch restriction.
            pointBalanceJpaRepository.fetchGrantsForUpdate(userId);
            pointBalanceJpaRepository.fetchUsagesForUpdate(userId);
        }
        return balance;
    }
}
