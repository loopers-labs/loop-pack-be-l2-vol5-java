package com.loopers.domain.point;

import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** DB 없이 포인트 서비스를 확인하기 위한 저장 구현. */
public class FakePointRepository implements PointRepository {

    private final Map<Long, Point> pointsByUserId = new LinkedHashMap<>();
    private long sequence = 0L;

    @Override
    public Point save(Point point) {
        if (point.getId() == null || point.getId() == 0L) {
            ReflectionTestUtils.setField(point, "id", ++sequence);
        }
        pointsByUserId.put(point.getUserId(), point);
        return point;
    }

    @Override
    public Optional<Point> findByUserId(Long userId) {
        return Optional.ofNullable(pointsByUserId.get(userId));
    }

    public int count() {
        return pointsByUserId.size();
    }
}
