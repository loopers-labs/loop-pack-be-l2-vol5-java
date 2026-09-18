package com.loopers.domain.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.ZonedDateTime;

@Entity
@Table(name = "user")
public class User {

    @Id
    private Long id;

    @Column(name = "point_balance", nullable = false)
    private long balance;

    @Column(name = "created_at", nullable = false, updatable = false)
    private ZonedDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private ZonedDateTime updatedAt;

    protected User() {
    }

    public User(long id) {
        this.id = id;
        this.balance = 0L;
    }

    public Long getId() {
        return id;
    }

    public long getBalance() {
        return balance;
    }

    public void charge(long amount) {
        requirePositiveAmount(amount);
        try {
            this.balance = Math.addExact(balance, amount);
        } catch (ArithmeticException exception) {
            throw new PointsException(PointsException.Reason.BALANCE_LIMIT_EXCEEDED);
        }
    }

    public void deduct(long amount) {
        validateDeduction(amount);
        this.balance -= amount;
    }

    public void validateDeduction(long amount) {
        requirePositiveAmount(amount);
        if (amount > balance) {
            throw new PointsException(PointsException.Reason.INSUFFICIENT_POINTS);
        }
    }

    private void requirePositiveAmount(long amount) {
        if (amount <= 0L) {
            throw new PointsException(PointsException.Reason.INVALID_AMOUNT);
        }
    }

    @PrePersist
    private void prePersist() {
        ZonedDateTime now = ZonedDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    private void preUpdate() {
        this.updatedAt = ZonedDateTime.now();
    }
}
