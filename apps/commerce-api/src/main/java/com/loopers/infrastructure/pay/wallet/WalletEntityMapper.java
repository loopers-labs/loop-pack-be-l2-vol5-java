package com.loopers.infrastructure.pay.wallet;

import com.loopers.domain.pay.wallet.Wallet;
import org.springframework.stereotype.Component;

@Component
// 지갑 엔티티-도메인 변환기
public class WalletEntityMapper {
    // 엔티티를 도메인으로 변환
    public Wallet toDomain(WalletJpaEntity entity) {
        return Wallet.restore(entity.getUserId(), entity.getBalance());
    }

    // 도메인을 신규 엔티티로 변환
    public WalletJpaEntity toNewEntity(Wallet wallet) {
        return new WalletJpaEntity(wallet.getUserId(), wallet.getBalance());
    }

    // 기존 엔티티에 변경사항 반영
    public void apply(Wallet wallet, WalletJpaEntity entity) {
        entity.apply(wallet.getBalance());
    }
}
