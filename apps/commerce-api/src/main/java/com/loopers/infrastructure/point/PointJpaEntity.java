package com.loopers.infrastructure.point;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "points")
public class PointJpaEntity extends BaseEntity {

    @Column(name = "user_id", nullable = false, unique = true, updatable = false)
    private Long userId;

    @Column(name = "balance", nullable = false)
    private long balance;

    private PointJpaEntity(Long userId, long balance) {
        this.userId = userId;
        this.balance = balance;
    }

    public static PointJpaEntity create(Long userId, long balance) {
        return new PointJpaEntity(userId, balance);
    }

    public void changeBalance(long balance) {
        this.balance = balance;
    }
}
