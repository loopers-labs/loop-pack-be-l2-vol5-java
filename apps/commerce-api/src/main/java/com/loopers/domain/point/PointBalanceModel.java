package com.loopers.domain.point;

import com.loopers.domain.BaseEntity;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Entity
@Table(name = "point_balance")
public class PointBalanceModel extends BaseEntity {

    @Column(nullable = false, unique = true)
    private Long userId;

    @Column(nullable = false)
    private long balance;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "point_balance_id", nullable = false)
    private List<PointGrant> grants = new ArrayList<>();

    protected PointBalanceModel() {}

    public PointBalanceModel(Long userId) {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("사용자 ID는 양수여야 합니다.");
        }
        this.userId = userId;
    }

    public long getBalance() {
        return balance;
    }

    public void charge(long amount) {
        charge(amount, ZonedDateTime.now());
    }

    public void charge(long amount, ZonedDateTime grantedAt) {
        if (amount <= 0 || amount > 5_000_000L) {
            throw new IllegalArgumentException("충전 금액은 1원 이상 5,000,000원 이하여야 합니다.");
        }
        addGrant(amount, PointGrantType.CHARGE, grantedAt, null);
    }

    public void reward(long amount, ZonedDateTime grantedAt) {
        if (amount <= 0) {
            throw new IllegalArgumentException("적립 금액은 양수여야 합니다.");
        }
        addGrant(amount, PointGrantType.REWARD, grantedAt, grantedAt.withZoneSameInstant(ZoneId.of("Asia/Seoul"))
            .toLocalDate().plusYears(1).plusDays(1).atStartOfDay(ZoneId.of("Asia/Seoul")));
    }

    public void use(long amount) {
        use(amount, ZonedDateTime.now());
    }

    public void use(long amount, ZonedDateTime now) {
        if (amount < 0) {
            throw new IllegalArgumentException("사용 금액은 음수일 수 없습니다.");
        }
        expire(now);
        if (balance < amount) {
            throw new CoreException(ErrorType.CONFLICT, "포인트 잔액이 부족합니다.");
        }
        long remaining = amount;
        for (PointGrant grant : grants.stream().sorted(grantOrder()).toList()) {
            long usable = grant.remainingAmount();
            long used = Math.min(usable, remaining);
            if (used > 0) {
                grant.record(used, PointUsageType.PAYMENT, now);
                remaining -= used;
            }
            if (remaining == 0) {
                break;
            }
        }
        balance -= amount;
    }

    public void expire(ZonedDateTime now) {
        for (PointGrant grant : grants) {
            if (grant.expiresAt(now) && grant.remainingAmount() > 0) {
                long expired = grant.remainingAmount();
                grant.record(expired, PointUsageType.EXPIRATION, now);
                balance -= expired;
            }
        }
    }

    private void addGrant(long amount, PointGrantType type, ZonedDateTime grantedAt, ZonedDateTime expiresAt) {
        try {
            balance = Math.addExact(balance, amount);
        } catch (ArithmeticException exception) {
            throw new CoreException(ErrorType.BAD_REQUEST, "포인트 잔액 범위를 초과했습니다.");
        }
        grants.add(new PointGrant(amount, type, grantedAt, expiresAt));
    }

    private static Comparator<PointGrant> grantOrder() {
        return Comparator.comparing(PointGrant::getExpiresAt, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(PointGrant::getGrantedAt)
            .thenComparing(PointGrant::getId, Comparator.nullsLast(Comparator.naturalOrder()));
    }
}
