package com.loopers.infrastructure.point;

import com.loopers.domain.BaseEntity;
import com.loopers.domain.common.Money;
import com.loopers.domain.point.UserPoint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(
    name = "user_point",
    uniqueConstraints = @UniqueConstraint(name = "uk_user_point_user_id", columnNames = "user_id")
)
class UserPointEntity extends BaseEntity {

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "balance", nullable = false)
    private long balance;

    protected UserPointEntity() {}

    static UserPointEntity open(Long userId) {
        UserPointEntity entity = new UserPointEntity();
        entity.userId = userId;
        entity.balance = 0L;
        return entity;
    }

    void changeBalance(long balance) {
        this.balance = balance;
    }

    UserPoint toDomain() {
        return UserPoint.restore(userId, Money.of(balance));
    }

    Long getUserId() {
        return userId;
    }
}
