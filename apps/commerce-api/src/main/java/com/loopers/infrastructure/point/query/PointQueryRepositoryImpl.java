package com.loopers.infrastructure.point.query;

import com.loopers.application.point.query.PointQueryRepository;
import com.loopers.application.point.query.PointView;
import com.querydsl.core.types.Projections;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

import static com.loopers.domain.point.QPointModel.pointModel;

@RequiredArgsConstructor
@Component
public class PointQueryRepositoryImpl implements PointQueryRepository {
    private final JPAQueryFactory queryFactory;

    @Override
    public Optional<PointView.Balance> findBalanceByUserId(Long userId) {
        if (userId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(queryFactory
            .select(Projections.constructor(PointView.Balance.class, pointModel.userId, pointModel.balance))
            .from(pointModel)
            .where(pointModel.userId.eq(userId))
            .fetchOne());
    }
}
