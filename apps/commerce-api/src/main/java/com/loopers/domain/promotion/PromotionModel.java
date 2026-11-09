package com.loopers.domain.promotion;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.ZonedDateTime;

/**
 * AG-07 프로모션 (TB-15 promotion). 범용 프로모션 메타데이터. 종류별 설정은 종류별 테이블이 promotion_id 로 참조한다.
 * 참여 가능 여부는 status 만 본다. starts_at·ends_at 은 메타데이터이고 판단에 쓰지 않는다.
 * 전이: READY —activate→ ACTIVE —pause→ PAUSED —resume→ ACTIVE. READY·ACTIVE·PAUSED —end→ ENDED (끝).
 */
@Entity
@Table(name = "promotion")
public class PromotionModel extends BaseEntity {
    public static final int NAME_MAX_LENGTH = 100;

    @Column(name = "name", nullable = false, length = NAME_MAX_LENGTH)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, updatable = false, length = 30)
    private PromotionType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PromotionStatus status;

    @Column(name = "starts_at", nullable = false)
    private ZonedDateTime startsAt;

    @Column(name = "ends_at", nullable = false)
    private ZonedDateTime endsAt;

    protected PromotionModel() {}

    private PromotionModel(String name, PromotionType type, ZonedDateTime startsAt, ZonedDateTime endsAt) {
        this.name = name;
        this.type = type;
        this.status = PromotionStatus.READY;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
    }

    /** 새 프로모션. READY 로 시작한다. */
    public static PromotionModel create(String name, PromotionType type, ZonedDateTime startsAt, ZonedDateTime endsAt) {
        if (name == null || name.isBlank() || name.length() > NAME_MAX_LENGTH) {
            throw new IllegalArgumentException("프로모션 이름은 1~" + NAME_MAX_LENGTH + "자여야 합니다.");
        }
        if (type == null || startsAt == null || endsAt == null) {
            throw new IllegalArgumentException("프로모션의 type·startsAt·endsAt 이 없습니다.");
        }
        if (!startsAt.isBefore(endsAt)) {
            throw new IllegalArgumentException("프로모션 시작 시각은 종료 시각보다 앞이어야 합니다.");
        }
        return new PromotionModel(name, type, startsAt, endsAt);
    }

    public void activate() {
        ensureStatus("열기", PromotionStatus.READY);
        this.status = PromotionStatus.ACTIVE;
    }

    public void pause() {
        ensureStatus("일시 중지", PromotionStatus.ACTIVE);
        this.status = PromotionStatus.PAUSED;
    }

    public void resume() {
        ensureStatus("재개", PromotionStatus.PAUSED);
        this.status = PromotionStatus.ACTIVE;
    }

    public void end() {
        ensureStatus("종료", PromotionStatus.READY, PromotionStatus.ACTIVE, PromotionStatus.PAUSED);
        this.status = PromotionStatus.ENDED;
    }

    /** 참여 가능 여부. 상태만 본다. */
    public boolean isActive() {
        return status == PromotionStatus.ACTIVE;
    }

    private void ensureStatus(String action, PromotionStatus... allowed) {
        for (PromotionStatus s : allowed) {
            if (status == s) {
                return;
            }
        }
        throw new IllegalStateException("프로모션 " + action + " 불가 [id = " + getId() + ", status = " + status + "]");
    }

    public String getName() {
        return name;
    }

    public PromotionType getType() {
        return type;
    }

    public PromotionStatus getStatus() {
        return status;
    }

    public ZonedDateTime getStartsAt() {
        return startsAt;
    }

    public ZonedDateTime getEndsAt() {
        return endsAt;
    }
}
