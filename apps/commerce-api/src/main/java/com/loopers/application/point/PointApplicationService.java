package com.loopers.application.point;

import com.loopers.application.point.port.PointRepository;
import com.loopers.domain.point.Point;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class PointApplicationService {
    private final PointRepository pointRepository;

    public PointApplicationService(PointRepository pointRepository) {
        this.pointRepository = pointRepository;
    }

    public long balance(long userId) {
        return pointRepository.findOrCreateForUpdate(userId).getBalance().value();
    }

    public long charge(long userId, long amount) {
        Point point = pointRepository.findOrCreateForUpdate(userId);
        point.charge(amount);
        pointRepository.save(point);
        return point.getBalance().value();
    }
}
