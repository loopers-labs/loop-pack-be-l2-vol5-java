package com.loopers.point.adapter.out.persistence;

import com.loopers.common.domain.Money;
import com.loopers.point.application.port.out.PointGroupPort;
import com.loopers.point.domain.PointGroup;
import com.loopers.point.domain.QPointGroup;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.util.List;

@RequiredArgsConstructor
@Component
public class PointGroupPersistenceAdapter implements PointGroupPort {

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

    @Override
    public List<PointGroup> findRemainingByUserIdForUpdate(Long userId) {
        return queryFactory.selectFrom(POINT_GROUP)
                .where(POINT_GROUP.userId.eq(userId), POINT_GROUP.remaining.gt(Money.ZERO))
                .orderBy(POINT_GROUP.expiresAt.asc(), POINT_GROUP.id.asc())
//                .setLockMode(LockModeType.PESSIMISTIC_WRITE) // TODO - 테스트 이후 원복할 것
                .fetch();
    }

    /**
     * point_groups(expires_at) 인덱스로 대상을 찾는다. 처리한 그룹은 남은 금액이 0이 되어 다음 조회에서 빠진다.
     */
    @Override
    public List<PointGroup> findExpirableForUpdate(ZonedDateTime now, int limit) {
        return queryFactory.selectFrom(POINT_GROUP)
            .where(POINT_GROUP.expiresAt.loe(now), POINT_GROUP.remaining.gt(Money.ZERO))
            .orderBy(POINT_GROUP.expiresAt.asc(), POINT_GROUP.id.asc())
            .limit(limit)
//            .setLockMode(LockModeType.PESSIMISTIC_WRITE) // TODO - 테스트 이후 원복할 것
            .fetch();
    }
}
