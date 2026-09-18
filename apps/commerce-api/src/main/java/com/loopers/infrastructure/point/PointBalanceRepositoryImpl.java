package com.loopers.infrastructure.point;

import com.loopers.domain.point.PointBalance;
import com.loopers.domain.point.PointBalanceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
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
        PointBalanceJpaEntity entity = point.getId() == null
            ? new PointBalanceJpaEntity(point)
            : repository.findById(point.getId()).orElseThrow();
        entity.update(point);
        return repository.save(entity).toDomain();
    }
}
