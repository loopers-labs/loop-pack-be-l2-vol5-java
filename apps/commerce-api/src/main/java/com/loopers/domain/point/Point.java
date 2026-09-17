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

    private Point(Long userId, PointBalance balance) {
        this.userId = userId;
        this.balance = balance;
    }

    public static Point create(Long userId) {
        return new Point(userId, new PointBalance(0L));
    }

    public static Point create(Long userId, PointBalance balance) {
        return new Point(userId, balance);
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

    public void pay(long amount) {
        balance = balance.subtract(amount);
    }
}
