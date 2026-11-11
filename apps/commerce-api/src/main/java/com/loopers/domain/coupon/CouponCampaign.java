package com.loopers.domain.coupon;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

import java.time.Instant;
import java.util.Objects;

public class CouponCampaign {

    private final Long id;
    private final String name;
    private final long totalQuota;
    private final String benefitDescription;
    private final Instant startAt;
    private final Instant endAt;
    private long issuedCount;
    private Instant terminatedAt;

    private CouponCampaign(Long id, String name, long totalQuota, long issuedCount,
                           String benefitDescription, Instant startAt, Instant endAt, Instant terminatedAt) {
        this.id = id;
        this.name = requireText(name, "캠페인 이름은 필수입니다.");
        this.benefitDescription = requireText(benefitDescription, "쿠폰 혜택 설명은 필수입니다.");
        if (totalQuota <= 0) {
            throw new CoreException(ErrorType.BAD_REQUEST, "총 발급 한도는 1 이상이어야 합니다.");
        }
        if (startAt == null || endAt == null || !startAt.isBefore(endAt)) {
            throw new CoreException(ErrorType.BAD_REQUEST, "발급 시작 시각은 종료 시각보다 빨라야 합니다.");
        }
        if (issuedCount < 0 || issuedCount > totalQuota) {
            throw new IllegalArgumentException("발급 수는 0 이상 총 발급 한도 이하여야 합니다.");
        }
        if (terminatedAt != null && (issuedCount != totalQuota
            || terminatedAt.isBefore(startAt) || !terminatedAt.isBefore(endAt))) {
            throw new IllegalArgumentException("조기 품절 종료는 발급 기간 안에 총량을 소진한 경우에만 기록합니다.");
        }
        this.totalQuota = totalQuota;
        this.issuedCount = issuedCount;
        this.startAt = startAt;
        this.endAt = endAt;
        this.terminatedAt = terminatedAt;
    }

    public static CouponCampaign create(String name, long totalQuota, String benefitDescription,
                                        Instant startAt, Instant endAt) {
        return new CouponCampaign(null, name, totalQuota, 0, benefitDescription, startAt, endAt, null);
    }

    public static CouponCampaign reconstitute(Long id, String name, long totalQuota, long issuedCount,
                                              String benefitDescription, Instant startAt, Instant endAt,
                                              Instant terminatedAt) {
        return new CouponCampaign(id, name, totalQuota, issuedCount, benefitDescription, startAt, endAt, terminatedAt);
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public long getTotalQuota() { return totalQuota; }
    public long getIssuedCount() { return issuedCount; }
    public String getBenefitDescription() { return benefitDescription; }
    public Instant getStartAt() { return startAt; }
    public Instant getEndAt() { return endAt; }
    public Instant getTerminatedAt() { return terminatedAt; }

    public void validateIssuable(Instant issueNow) {
        Objects.requireNonNull(issueNow, "issueNow");
        if (terminatedAt != null || issuedCount == totalQuota) {
            throw new CoreException(ErrorType.CONFLICT, "쿠폰이 모두 발급된 캠페인입니다.");
        }
        if (issueNow.isBefore(startAt) || !issueNow.isBefore(endAt)) {
            throw new CoreException(ErrorType.CONFLICT, "쿠폰 발급 기간이 아닙니다.");
        }
    }

    public void recordIssue(Instant issueNow) {
        validateIssuable(issueNow);
        issuedCount++;
        if (issuedCount == totalQuota) {
            terminatedAt = issueNow;
        }
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new CoreException(ErrorType.BAD_REQUEST, message);
        }
        return value;
    }
}
