package com.loopers.application.pay.query;

import java.util.Optional;

// 지갑 잔액 조회 쿼리
public interface WalletQueryDao {
    Optional<Long> findBalance(long userId);
}
