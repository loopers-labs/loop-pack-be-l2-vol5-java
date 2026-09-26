package com.loopers.infrastructure.point;

import com.loopers.domain.point.Point;
import com.loopers.domain.point.PointRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

@RequiredArgsConstructor
@Component
public class PointRepositoryImpl implements PointRepository {

    private final PointJpaRepository pointJpaRepository;

    @Override
    public Optional<Point> findByUserId(Long userId) {
        return pointJpaRepository.findByUserId(userId).map(PointJpaMapper::toDomain);
    }

    @Override
    public Point save(Point point) {
        PointJpaEntity entity = pointJpaRepository.findByUserId(point.getUserId())
            .map(found -> {
                PointJpaMapper.update(point, found);
                return found;
            })
            .orElseGet(() -> PointJpaMapper.toNewEntity(point));
        return PointJpaMapper.toDomain(pointJpaRepository.save(entity));
    }
}
