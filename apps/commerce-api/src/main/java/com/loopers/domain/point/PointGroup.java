package com.loopers.domain.point;

import com.loopers.domain.BaseEntity;
import com.loopers.domain.common.Money;
import com.loopers.domain.common.MoneyConverter;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Period;
import java.time.ZonedDateTime;

/**
 * 충전 한 건이 만든 포인트 묶음 (ADR-04).
 * 남은 금액과 만료 시각을 가지고, "지금 쓸 수 있나", "남은 금액 안에서 이만큼 써 줘", "만료 처리해 줘"에 답한다.
 */
@Entity
@Table(
    name = "point_groups",
    indexes = {
        @Index(name = "idx_point_groups_user_expires", columnList = "user_id, expires_at"),
        @Index(name = "idx_point_groups_expires", columnList = "expires_at")
    }
)
public class PointGroup extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false)
    private PointGroupType type;

    @Convert(converter = MoneyConverter.class)
    @Column(name = "amount", nullable = false)
    private Money amount;

    @Convert(converter = MoneyConverter.class)
    @Column(name = "remaining", nullable = false)
    private Money remaining;

    @Column(name = "charged_at", nullable = false)
    private ZonedDateTime chargedAt;

    @Column(name = "expires_at", nullable = false)
    private ZonedDateTime expiresAt;

    protected PointGroup() {}

    private PointGroup(Long userId, PointGroupType type, Money amount, ZonedDateTime chargedAt, ZonedDateTime expiresAt) {
        this.userId = userId;
        this.type = type;
        this.amount = amount;
        this.remaining = amount;
        this.chargedAt = chargedAt;
        this.expiresAt = expiresAt;
    }

    /**
     * PNT-02: 충전 한 번이 그룹 하나를 만든다. 충전액은 양수여야 한다.
     */
    public static PointGroup charge(Long userId, Money amount, ZonedDateTime chargedAt, Period validity) {
        if (amount.isZero()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "충전 금액은 0보다 커야 합니다.");
        }
        return new PointGroup(userId, PointGroupType.CHARGE, amount, chargedAt, chargedAt.plus(validity));
    }

    /**
     * PNT-07·P-20: 만료 시각 정각부터는 쓸 수 없다.
     */
    public boolean isUsableAt(ZonedDateTime now) {
        return now.isBefore(expiresAt);
    }

    /**
     * PNT-06: 남은 금액 이하만 쓸 수 있다. 거절하면 남은 금액은 바뀌지 않는다.
     */
    public void use(Money usage, ZonedDateTime now) {
        if (!isUsableAt(now)) {
            throw new CoreException(ErrorType.CONFLICT, "만료된 포인트는 사용할 수 없습니다.");
        }
        if (usage.isGreaterThan(remaining)) {
            throw new CoreException(ErrorType.CONFLICT, "남은 포인트보다 많이 사용할 수 없습니다.");
        }
        this.remaining = remaining.minus(usage);
    }

    /**
     * PNT-08: 만료 시각이 된 그룹의 남은 금액을 0으로 만들고, 만료된 금액을 돌려준다. 다시 실행해도 결과가 같다.
     */
    public Money expire(ZonedDateTime now) {
        if (isUsableAt(now)) {
            throw new CoreException(ErrorType.CONFLICT, "아직 만료되지 않은 포인트입니다.");
        }
        Money expired = remaining;
        this.remaining = Money.ZERO;
        return expired;
    }

    public Long getUserId() {
        return userId;
    }

    public PointGroupType getType() {
        return type;
    }

    public Money getAmount() {
        return amount;
    }

    public Money getRemaining() {
        return remaining;
    }

    public ZonedDateTime getChargedAt() {
        return chargedAt;
    }

    public ZonedDateTime getExpiresAt() {
        return expiresAt;
    }
}
