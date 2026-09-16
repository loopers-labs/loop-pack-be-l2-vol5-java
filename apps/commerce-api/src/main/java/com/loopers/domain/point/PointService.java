package com.loopers.domain.point;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Component
public class PointService {

    private final PointRepository pointRepository;

    @Transactional
    public PointModel charge(Long userId, Long amount) {
        PointModel point = pointRepository.findByUserId(userId)
            .orElseGet(() -> new PointModel(userId));
        point.charge(amount);
        return pointRepository.save(point);
    }

    @Transactional(readOnly = true)
    public long getBalance(Long userId) {
        return pointRepository.findByUserId(userId)
            .map(PointModel::getBalance)
            .orElse(0L);
    }
}
