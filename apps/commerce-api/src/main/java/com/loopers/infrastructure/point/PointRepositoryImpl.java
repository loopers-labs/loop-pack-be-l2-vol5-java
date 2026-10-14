package com.loopers.infrastructure.point;

import com.loopers.domain.point.Point;
import com.loopers.domain.point.PointRepository;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.util.Optional;

import static com.loopers.domain.point.QPoint.point;

@RequiredArgsConstructor
@Component
public class PointRepositoryImpl implements PointRepository {
    private final PointJpaRepository pointJpaRepository;
    private final JPAQueryFactory queryFactory;

    @Override
    public Point save(Point point) {
        return pointJpaRepository.save(point);
    }

    @Override
    public Optional<Point> findByUserId(Long userId) {
        return pointJpaRepository.findByUserId(userId);
    }

    @Override
    public int deductIfEnough(Long userId, long amount) {
        return (int) queryFactory
            .update(point)
            .set(point.balance, point.balance.subtract(amount))
            .set(point.updatedAt, ZonedDateTime.now())
            .where(point.userId.eq(userId), point.balance.goe(amount))
            .execute();
    }

    @Override
    public int addBalance(Long userId, long amount) {
        return (int) queryFactory
            .update(point)
            .set(point.balance, point.balance.add(amount))
            .set(point.updatedAt, ZonedDateTime.now())
            .where(point.userId.eq(userId), point.balance.loe(Long.MAX_VALUE - amount))
            .execute();
    }
}
