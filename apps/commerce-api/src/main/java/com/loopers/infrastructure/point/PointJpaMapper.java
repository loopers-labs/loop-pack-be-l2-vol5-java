package com.loopers.infrastructure.point;

import com.loopers.domain.point.Point;
import com.loopers.domain.point.PointBalance;

final class PointJpaMapper {

    private PointJpaMapper() {}

    static Point toDomain(PointJpaEntity entity) {
        return Point.create(entity.getUserId(), new PointBalance(entity.getBalance()));
    }

    static PointJpaEntity toNewEntity(Point point) {
        return PointJpaEntity.create(point.getUserId(), point.getBalance().amount());
    }

    static void update(Point point, PointJpaEntity entity) {
        entity.changeBalance(point.getBalance().amount());
    }
}
