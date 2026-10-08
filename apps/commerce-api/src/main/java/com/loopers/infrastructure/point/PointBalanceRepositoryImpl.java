package com.loopers.infrastructure.point;

import com.loopers.domain.point.PointBalance;
import com.loopers.domain.point.PointBalanceRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
@Transactional
public class PointBalanceRepositoryImpl implements PointBalanceRepository {
    private final PointBalanceJpaRepository repository;

    @Override
    @Transactional(readOnly = true)
    public Optional<PointBalance> findByUserId(long userId) {
        return repository.findByUserId(userId).map(PointBalanceJpaEntity::toDomain);
    }

    @Override
    public PointBalance save(PointBalance point) {
        PointBalanceJpaEntity entity =
                point.getId() == null
                        ? new PointBalanceJpaEntity(point)
                        : repository.findById(point.getId()).orElseThrow();
        entity.update(point);
        return repository.save(entity).toDomain();
    }

    @Override
    public PointBalance charge(long userId, long amount) {
        PointBalance.validateAmount(amount);
        repository.initializeIfAbsent(userId);
        if (repository.charge(userId, amount, Long.MAX_VALUE - amount, ZonedDateTime.now()) == 0) {
            throw new CoreException(ErrorType.INVALID_REQUEST);
        }
        return repository.findByUserId(userId).orElseThrow().toDomain();
    }

    @Override
    public void deduct(long userId, long amount) {
        PointBalance.validateAmount(amount);
        if (repository.deduct(userId, amount, ZonedDateTime.now()) == 0) {
            throw new CoreException(ErrorType.INSUFFICIENT_POINTS);
        }
    }
}
