package com.loopers.application.point;

import com.loopers.application.point.port.PointRepository;
import com.loopers.domain.point.Point;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class PointApplicationService {
    private final PointRepository repository;
    public PointApplicationService(PointRepository repository) { this.repository = repository; }
    public long balance(long userId) { return repository.findOrCreateForUpdate(userId).getBalance().value(); }
    public long charge(long userId, long amount) {
        Point point = repository.findOrCreateForUpdate(userId);
        point.charge(amount);
        repository.save(point);
        return point.getBalance().value();
    }
}
