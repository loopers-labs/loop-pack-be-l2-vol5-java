package com.loopers.infrastructure.query.pay;

import com.loopers.application.pay.query.WalletQueryDao;
import com.loopers.infrastructure.persistence.pay.entity.QWalletJpaEntity;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
// QueryDSL 기반 지갑 조회
public class QueryDslWalletQueryDao implements WalletQueryDao {
    private static final QWalletJpaEntity WALLET = QWalletJpaEntity.walletJpaEntity;

    private final JPAQueryFactory queryFactory;

    // 사용자 지갑 잔액 조회
    @Override
    @Transactional(readOnly = true)
    public Optional<Long> findBalance(long userId) {
        Long balance = queryFactory.select(WALLET.balance)
            .from(WALLET)
            .where(WALLET.userId.eq(userId))
            .fetchOne();
        return Optional.ofNullable(balance);
    }
}
