package com.loopers.infrastructure.point;

import com.loopers.domain.common.Money;
import com.loopers.domain.point.PointGroup;
import com.loopers.domain.point.PointGroupRepository;
import com.loopers.domain.point.QPointGroup;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@RequiredArgsConstructor
@Component
public class PointGroupRepositoryImpl implements PointGroupRepository {

    private static final QPointGroup POINT_GROUP = QPointGroup.pointGroup;

    private final PointGroupJpaRepository pointGroupJpaRepository;
    private final JPAQueryFactory queryFactory;

    @Override
    public PointGroup save(PointGroup group) {
        return pointGroupJpaRepository.saveAndFlush(group);
    }

    /**
     * 남은 금액(Money)은 AttributeConverter가 정수 컬럼으로 옮기므로, 비교값도 Money.ZERO를 그대로 넘긴다.
     * 만료 시각이 이른 순, 같으면 먼저 충전한 순(id)으로 돌려준다 (PNT-05).
     */
    @Override
    public List<PointGroup> findRemainingByUserId(Long userId) {
        return queryFactory.selectFrom(POINT_GROUP)
            .where(POINT_GROUP.userId.eq(userId), POINT_GROUP.remaining.gt(Money.ZERO))
            .orderBy(POINT_GROUP.expiresAt.asc(), POINT_GROUP.id.asc())
            .fetch();
    }
}
