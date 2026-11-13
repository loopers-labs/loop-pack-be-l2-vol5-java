package com.loopers.domain.coupon;

import com.loopers.domain.BaseEntity;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.ZonedDateTime;

/**
 * 쿠폰 발급 캠페인. 총 발급 한도와 지금까지 나간 수를 DB 원본으로 갖는다(docs/week8/decisions.md D-1, INV-801).
 *
 * - 발급 판단(활성인가, 남았는가)과 발급 수 증가는 이 모델이 한다({@link #issueTo}). 호출자는 이 행을 {@code SELECT … FOR UPDATE}로
 *   읽은 뒤에만 부른다 — 그래야 판단에 쓴 값이 commit 시점까지 참이다(D-8). 일반 조회로 읽은 스냅샷으로 판단하면 마지막 한 장이
 *   두 번 나갈 수 있다.
 * - 이 행을 바꾸는 다른 경로(비활성화, 혜택 변경)도 같은 락으로 읽은 뒤에만 부른다(D-8).
 * - 발급 수는 줄이는 메서드가 없다. 동시 처리 자리(Redis)를 반환해도 발급 수는 그대로다(D-1).
 */
@Entity
@Table(name = "coupon_campaign")
public class CouponCampaign extends BaseEntity {

    public static final String INACTIVE_MESSAGE = "비활성 캠페인이라 쿠폰을 발급할 수 없습니다.";
    public static final String SOLD_OUT_MESSAGE = "캠페인 쿠폰이 모두 소진되었습니다.";

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "total_quota", nullable = false, updatable = false)
    private int totalQuota;

    @Column(name = "issued_count", nullable = false)
    private int issuedCount;

    @Embedded
    private CouponBenefit benefit;

    protected CouponCampaign() {}

    /**
     * 활성 상태, 발급 수 0으로 만든다.
     *
     * @throws CoreException BAD_REQUEST — 이름이 비었거나, 총 한도가 양수가 아니거나, 혜택이 없다.
     */
    public CouponCampaign(String name, int totalQuota, CouponBenefit benefit) {
        if (name == null || name.isBlank()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "캠페인 이름은 비어있을 수 없습니다.");
        }
        if (totalQuota <= 0) {
            throw new CoreException(ErrorType.BAD_REQUEST, "캠페인 총 발급 한도는 양수여야 합니다.");
        }
        if (benefit == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "캠페인 혜택은 비어있을 수 없습니다.");
        }
        this.name = name;
        this.active = true;
        this.totalQuota = totalQuota;
        this.issuedCount = 0;
        this.benefit = benefit;
    }

    /**
     * 한 사용자에게 쿠폰 한 장을 발급한다: 판단하고, 발급 수를 1 올리고, 지금 혜택을 복사한 쿠폰을 돌려준다(D-8, D-15).
     * 돌려준 쿠폰을 같은 TX에서 저장해야 발급 수와 실제 쿠폰 수가 같다(INV-801). 저장이 실패하면 TX 전체를 rollback한다.
     *
     * <p>비활성과 품절은 둘 다 409지만 메시지가 다르다 — 요청자가 입력을 바꿔도 안 풀리고 캠페인 상태가 바뀌어야 풀린다(D-9).
     * 비활성을 먼저 본다 — 비활성 캠페인은 남은 수와 상관없이 발급하지 않는다.
     *
     * @throws CoreException CONFLICT — 비활성({@link #INACTIVE_MESSAGE}) 또는 품절({@link #SOLD_OUT_MESSAGE}).
     */
    public IssuedCoupon issueTo(Long userId, ZonedDateTime issuedAt) {
        if (!active) {
            throw new CoreException(ErrorType.CONFLICT, INACTIVE_MESSAGE);
        }
        if (issuedCount >= totalQuota) {
            throw new CoreException(ErrorType.CONFLICT, SOLD_OUT_MESSAGE);
        }
        IssuedCoupon coupon = IssuedCoupon.issue(getId(), userId, benefit.copy(), issuedAt);
        this.issuedCount++;
        return coupon;
    }

    /** 신규 발급을 멈춘다. 이미 나간 쿠폰과 발급 수는 그대로다. 멱등하다. */
    public void deactivate() {
        this.active = false;
    }

    /** 앞으로 발급할 쿠폰의 혜택을 바꾼다. 이미 발급된 쿠폰은 발급 순간의 값을 따로 갖고 있어 바뀌지 않는다(INV-806). */
    public void changeBenefit(CouponBenefit benefit) {
        if (benefit == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "캠페인 혜택은 비어있을 수 없습니다.");
        }
        this.benefit = benefit;
    }

    @Override
    protected void guard() {
        if (issuedCount < 0 || issuedCount > totalQuota) {
            throw new IllegalStateException(
                "캠페인 발급 수가 총 한도 범위를 벗어났습니다. (issuedCount=" + issuedCount + ", totalQuota=" + totalQuota + ")");
        }
    }

    public String getName() {
        return name;
    }

    public boolean isActive() {
        return active;
    }

    public int getTotalQuota() {
        return totalQuota;
    }

    public int getIssuedCount() {
        return issuedCount;
    }

    public CouponBenefit getBenefit() {
        return benefit;
    }
}
