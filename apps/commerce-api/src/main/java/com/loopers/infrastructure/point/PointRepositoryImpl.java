package com.loopers.infrastructure.point;

import com.loopers.domain.point.PointModel;
import com.loopers.domain.point.PointRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

@RequiredArgsConstructor
@Component
public class PointRepositoryImpl implements PointRepository {
    private final PointJpaRepository pointJpaRepository;

    @Override
    public PointModel save(PointModel point) {
        return pointJpaRepository.save(point);
    }

    @Override
    public Optional<PointModel> findByUserId(Long userId) {
        if (userId == null) {
            return Optional.empty();
        }
        return pointJpaRepository.findByUserId(userId);
    }
}
