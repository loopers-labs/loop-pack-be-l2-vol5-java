package com.loopers.domain.point;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "points")
public class Point extends BaseEntity {

    @Column(name = "user_id", nullable = false, unique = true, updatable = false)
    private Long userId;

    @Column(name = "balance", nullable = false)
    private PointBalance balance;

    protected Point() {}

    public Point(Long userId) {
        this(userId, new PointBalance(0L));
    }

    public Point(Long userId, PointBalance balance) {
        this.userId = userId;
        this.balance = balance;
    }

    public Long getUserId() {
        return userId;
    }

    public PointBalance getBalance() {
        return balance;
    }

    public void charge(long amount) {
        balance = balance.add(amount);
    }
}
