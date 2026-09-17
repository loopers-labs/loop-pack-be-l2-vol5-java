package com.loopers.infrastructure.point;

import com.loopers.domain.point.PointHistoryModel;
import com.loopers.domain.point.PointHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Component
public class PointHistoryRepositoryImpl implements PointHistoryRepository {

    private final PointHistoryJpaRepository pointHistoryJpaRepository;

    @Override
    public PointHistoryModel save(PointHistoryModel history) {
        return pointHistoryJpaRepository.save(history);
    }
}
