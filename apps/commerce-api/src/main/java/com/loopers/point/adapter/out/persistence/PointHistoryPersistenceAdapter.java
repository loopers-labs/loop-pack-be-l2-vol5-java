package com.loopers.point.adapter.out.persistence;

import com.loopers.point.application.port.out.PointHistoryPort;
import com.loopers.point.domain.PointHistory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@RequiredArgsConstructor
@Component
public class PointHistoryPersistenceAdapter implements PointHistoryPort {

    private final PointHistoryJpaRepository pointHistoryJpaRepository;

    @Override
    public PointHistory save(PointHistory history) {
        return pointHistoryJpaRepository.save(history);
    }

    @Override
    public List<PointHistory> saveAll(List<PointHistory> histories) {
        return pointHistoryJpaRepository.saveAll(histories);
    }
}
