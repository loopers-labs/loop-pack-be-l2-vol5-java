package com.loopers.infrastructure.persistence.pay.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.loopers.domain.pay.model.Wallet;
import com.loopers.domain.pay.repository.WalletRepository;
import com.loopers.domain.shared.Money;
import com.loopers.support.test.IntegrationTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@IntegrationTest
class WalletRepositoryIntegrationTest {
    @Autowired
    private WalletRepository walletRepository;
    @Autowired
    private EntityManager entityManager;

    @DisplayName("사용자별 초기 지갑을 저장하고 충전한 뒤 영속성 컨텍스트를 비워도 잔액을 보존한다")
    @Test
    @Transactional
    void savesAndChargesWallet() {
        Wallet wallet = walletRepository.save(Wallet.zero(1L));
        wallet.charge(Money.positive(1_000L));
        walletRepository.save(wallet);
        entityManager.flush();
        entityManager.clear();

        Wallet restored = walletRepository.findByUserId(1L).orElseThrow();

        assertThat(restored.getUserId()).isEqualTo(1L);
        assertThat(restored.getBalance()).isEqualTo(1_000L);
    }

    @DisplayName("없는 사용자의 지갑은 빈 결과를 반환한다")
    @Test
    @Transactional
    void returnsEmpty_whenWalletDoesNotExist() {
        assertThat(walletRepository.findByUserId(999L)).isEmpty();
    }
}
