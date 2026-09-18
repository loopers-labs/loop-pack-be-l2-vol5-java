package com.loopers.infrastructure.point;

import com.loopers.domain.BaseEntity;
import com.loopers.domain.point.PointBalance;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "point_balances")
public class PointBalanceJpaEntity extends BaseEntity {
    @Column(nullable = false, unique = true) private long userId;
    @Column(nullable = false) private long balance;

    protected PointBalanceJpaEntity() {}

    public PointBalanceJpaEntity(PointBalance point) {
        userId = point.getUserId();
        update(point);
    }

    public void update(PointBalance point) {
        balance = point.getBalance();
    }

    public PointBalance toDomain() {
        return PointBalance.restore(getId(), userId, balance);
    }
}
