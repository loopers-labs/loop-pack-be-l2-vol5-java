package com.loopers.coupon.domain;

import com.loopers.domain.BaseEntity;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import org.hibernate.annotations.Comment;

import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.Set;

/**
 * 캠페인에 담긴 쿠폰 한 장의 할인 규칙 (D-5). 캠페인은 식별자로만 안다.
 * <p>
 * 이번 주는 발급까지만 한다. 주문에 적용하는 규칙(최소 주문 금액·적용 상품·중복 사용)은 저장하고 생성 때 검증만 한다 (D-5).
 */
@Entity
@Table(name = "coupon", indexes = @Index(name = "idx_coupon_campaign", columnList = "campaign_id"))
public class Coupon extends BaseEntity {

    static final int NAME_MAX_LENGTH = 100;
    static final long RATE_MIN = 1;
    static final long RATE_MAX = 100;

    @Column(name = "campaign_id", nullable = false)
    @Comment("이 쿠폰을 담은 캠페인")
    private Long campaignId;

    @Column(name = "name", nullable = false, length = NAME_MAX_LENGTH)
    @Comment("쿠폰 이름, 1–100자 (CPN-04)")
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "discount_type", nullable = false, length = 20)
    @Comment("FIXED 정액 | RATE 정률 (CPN-04)")
    private DiscountType discountType;

    @Column(name = "discount_value", nullable = false)
    @Comment("FIXED: 할인액(원, 1 이상) · RATE: 할인율(%, 1–100)")
    private long discountValue;

    @Column(name = "max_discount_amount")
    @Comment("RATE의 최대 할인액(원). FIXED는 없음")
    private Long maxDiscountAmount;

    @Column(name = "min_order_amount", nullable = false)
    @Comment("쓸 수 있는 최소 주문 금액(원), 0 이상")
    private long minOrderAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false, length = 20)
    @Comment("ALL 모든 상품 | PRODUCTS 적용 상품만 (CPN-05)")
    private CouponScope scope;

    @ElementCollection
    @CollectionTable(name = "coupon_applicable_product", joinColumns = @JoinColumn(name = "coupon_id"))
    @Column(name = "product_id", nullable = false)
    private Set<Long> applicableProductIds = new HashSet<>();

    @Enumerated(EnumType.STRING)
    @Column(name = "validity_type", nullable = false, length = 20)
    @Comment("FIXED_PERIOD 정해진 기간 | DAYS_FROM_ISSUE 발급 후 N일 (CPN-06)")
    private ValidityType validityType;

    @Column(name = "valid_from")
    @Comment("FIXED_PERIOD 사용 시작 시각")
    private ZonedDateTime validFrom;

    @Column(name = "valid_until")
    @Comment("FIXED_PERIOD 사용 끝 시각")
    private ZonedDateTime validUntil;

    @Column(name = "valid_days")
    @Comment("DAYS_FROM_ISSUE 발급 후 사용 가능 일수, 1 이상")
    private Integer validDays;

    @Column(name = "stackable", nullable = false)
    @Comment("다른 쿠폰과 함께 쓸 수 있는가. W1 계약 A-1(한 주문에 한 장)과 부딪쳐 사용 기능을 만들 때 다시 정한다")
    private boolean stackable;

    protected Coupon() {}

    private Coupon(Long campaignId, CouponDraft draft) {
        this.campaignId = campaignId;
        this.name = validName(draft.name());
        validDiscount(draft.discountType(), draft.discountValue(), draft.maxDiscountAmount());
        this.discountType = draft.discountType();
        this.discountValue = draft.discountValue();
        this.maxDiscountAmount = draft.maxDiscountAmount();
        this.minOrderAmount = validMinOrderAmount(draft.minOrderAmount());
        validScope(draft.scope(), draft.applicableProductIds());
        this.scope = draft.scope();
        this.applicableProductIds = new HashSet<>(draft.applicableProductIds());
        validValidity(draft.validityType(), draft.validFrom(), draft.validUntil(), draft.validDays());
        this.validityType = draft.validityType();
        this.validFrom = draft.validFrom();
        this.validUntil = draft.validUntil();
        this.validDays = draft.validDays();
        this.stackable = draft.stackable();
    }

    /**
     * CPN-04·05·06: 할인·적용 범위·유효 기간 규칙을 지켜야 만든다. 적용 상품이 살아 있는지는 응용 서비스가 확인한다.
     */
    public static Coupon of(Long campaignId, CouponDraft draft) {
        return new Coupon(campaignId, draft);
    }

    /**
     * CPN-07: 발급 시각에 이 쿠폰이 언제 만료되는가. FIXED_PERIOD는 끝 시각, DAYS_FROM_ISSUE는 발급 시각 + 일수.
     */
    public ZonedDateTime expiresAtWhenIssuedAt(ZonedDateTime issuedAt) {
        return switch (validityType) {
            case FIXED_PERIOD -> validUntil;
            case DAYS_FROM_ISSUE -> issuedAt.plusDays(validDays);
        };
    }

    private static String validName(String name) {
        if (name == null || name.isBlank()) {
            throw badRequest("쿠폰 이름은 비어 있을 수 없습니다.");
        }
        String trimmed = name.strip();
        if (trimmed.length() > NAME_MAX_LENGTH) {
            throw badRequest("쿠폰 이름은 " + NAME_MAX_LENGTH + "자 이하여야 합니다.");
        }
        return trimmed;
    }

    private static void validDiscount(DiscountType type, long value, Long maxDiscountAmount) {
        if (type == null) {
            throw badRequest("할인 방식은 필수입니다.");
        }
        switch (type) {
            case FIXED -> {
                if (value < 1) {
                    throw badRequest("정액 할인액은 1원 이상이어야 합니다.");
                }
                if (maxDiscountAmount != null) {
                    throw badRequest("정액 쿠폰에는 최대 할인액을 둘 수 없습니다.");
                }
            }
            case RATE -> {
                if (value < RATE_MIN || value > RATE_MAX) {
                    throw badRequest("정률 할인율은 1–100% 사이여야 합니다.");
                }
                if (maxDiscountAmount != null && maxDiscountAmount < 1) {
                    throw badRequest("최대 할인액은 1원 이상이어야 합니다.");
                }
            }
        }
    }

    private static long validMinOrderAmount(long minOrderAmount) {
        if (minOrderAmount < 0) {
            throw badRequest("최소 주문 금액은 0원 이상이어야 합니다.");
        }
        return minOrderAmount;
    }

    private static void validScope(CouponScope scope, Set<Long> productIds) {
        if (scope == null) {
            throw badRequest("적용 범위는 필수입니다.");
        }
        if (scope == CouponScope.ALL && !productIds.isEmpty()) {
            throw badRequest("모든 상품에 쓰는 쿠폰에는 적용 상품을 지정할 수 없습니다.");
        }
        if (scope == CouponScope.PRODUCTS && productIds.isEmpty()) {
            throw badRequest("특정 상품 쿠폰에는 적용 상품이 1개 이상 있어야 합니다.");
        }
    }

    private static void validValidity(ValidityType type, ZonedDateTime from, ZonedDateTime until, Integer days) {
        if (type == null) {
            throw badRequest("유효 기간 방식은 필수입니다.");
        }
        switch (type) {
            case FIXED_PERIOD -> {
                if (from == null || until == null) {
                    throw badRequest("정해진 기간 쿠폰은 사용 시작·끝 시각이 필요합니다.");
                }
                if (!from.isBefore(until)) {
                    throw badRequest("사용 시작 시각은 끝 시각보다 앞서야 합니다.");
                }
                if (days != null) {
                    throw badRequest("정해진 기간 쿠폰에는 사용 일수를 둘 수 없습니다.");
                }
            }
            case DAYS_FROM_ISSUE -> {
                if (days == null || days < 1) {
                    throw badRequest("발급 후 사용 일수는 1일 이상이어야 합니다.");
                }
                if (from != null || until != null) {
                    throw badRequest("발급 후 N일 쿠폰에는 사용 시작·끝 시각을 둘 수 없습니다.");
                }
            }
        }
    }

    private static CoreException badRequest(String message) {
        return new CoreException(ErrorType.BAD_REQUEST, message);
    }

    public Long getCampaignId() {
        return campaignId;
    }

    public String getName() {
        return name;
    }

    public DiscountType getDiscountType() {
        return discountType;
    }

    public long getDiscountValue() {
        return discountValue;
    }

    public Long getMaxDiscountAmount() {
        return maxDiscountAmount;
    }

    public long getMinOrderAmount() {
        return minOrderAmount;
    }

    public CouponScope getScope() {
        return scope;
    }

    public Set<Long> getApplicableProductIds() {
        return Set.copyOf(applicableProductIds);
    }

    public ValidityType getValidityType() {
        return validityType;
    }

    public ZonedDateTime getValidFrom() {
        return validFrom;
    }

    public ZonedDateTime getValidUntil() {
        return validUntil;
    }

    public Integer getValidDays() {
        return validDays;
    }

    public boolean isStackable() {
        return stackable;
    }
}
