package com.loopers.infrastructure.pay.wallet;

import com.loopers.application.pay.wallet.WalletQueryDao;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
// JdbcClient 기반 지갑 조회
public class JdbcWalletQueryDao implements WalletQueryDao {
    private final JdbcClient jdbcClient;

    // 사용자 지갑 잔액 조회
    @Override
    @Transactional(readOnly = true)
    public Optional<Long> findBalance(long userId) {
        return jdbcClient.sql("SELECT balance FROM wallets WHERE user_id = :userId")
            .param("userId", userId)
            .query(Long.class)
            .optional();
    }
}
