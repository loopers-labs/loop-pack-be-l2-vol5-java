package com.loopers.infrastructure.query.shopping;

import com.loopers.application.shopping.query.UserQueryDao;
import com.loopers.application.shopping.query.UserView;
import com.loopers.infrastructure.persistence.shopping.entity.QUserJpaEntity;
import com.querydsl.core.types.Projections;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
// QueryDSL 기반 사용자 조회 DAO
public class QueryDslUserQueryDao implements UserQueryDao {
    private static final QUserJpaEntity USER = QUserJpaEntity.userJpaEntity;

    private final JPAQueryFactory queryFactory;

    // ID로 사용자 조회
    @Override
    @Transactional(readOnly = true)
    public Optional<UserView> findById(long userId) {
        UserView view = queryFactory.select(Projections.constructor(UserView.class, USER.id))
            .from(USER)
            .where(USER.id.eq(userId))
            .fetchOne();
        return Optional.ofNullable(view);
    }
}
