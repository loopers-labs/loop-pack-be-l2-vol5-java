package com.loopers.infrastructure.pay.wallet;

import com.loopers.domain.pay.wallet.Wallet;
import com.loopers.domain.pay.wallet.WalletRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
// 지갑 저장소 구현체
public class WalletRepositoryImpl implements WalletRepository {
    private final WalletJpaRepository walletJpaRepository;
    private final WalletEntityMapper mapper;

    // 있으면 갱신, 없으면 신규 저장
    @Override
    public Wallet save(Wallet wallet) {
        Optional<WalletJpaEntity> existing = walletJpaRepository.findById(wallet.getUserId());
        WalletJpaEntity entity;
        if (existing.isEmpty()) {
            entity = mapper.toNewEntity(wallet);
        } else {
            entity = existing.get();
            mapper.apply(wallet, entity);
        }
        return mapper.toDomain(walletJpaRepository.save(entity));
    }

    @Override
    public Optional<Wallet> findByUserId(long userId) {
        return walletJpaRepository.findById(userId).map(mapper::toDomain);
    }

    // 비관적 쓰기 잠금으로 조회
    @Override
    public Optional<Wallet> findByUserIdForUpdate(long userId) {
        return walletJpaRepository.findByUserIdForUpdate(userId).map(mapper::toDomain);
    }
}
