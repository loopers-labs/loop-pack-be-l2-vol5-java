package com.loopers.infrastructure.pay.wallet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "wallets")
// 지갑 JPA 엔티티
public class WalletJpaEntity {
    @Id
    @Column(name = "user_id")
    private Long userId;
    @Column(nullable = false)
    private long balance;

    protected WalletJpaEntity() {}

    WalletJpaEntity(long userId, long balance) {
        this.userId = userId;
        this.balance = balance;
    }

    void apply(long balance) {
        this.balance = balance;
    }

    public Long getUserId() {
        return userId;
    }

    public long getBalance() {
        return balance;
    }
}
