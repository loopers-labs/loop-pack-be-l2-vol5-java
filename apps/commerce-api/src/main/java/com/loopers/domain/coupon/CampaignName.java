package com.loopers.domain.coupon;

public record CampaignName(String value) {

    private static final int MAX_LENGTH = 100;

    public CampaignName {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("캠페인 이름은 비어 있을 수 없습니다");
        }
        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                "캠페인 이름은 " + MAX_LENGTH + "자를 넘을 수 없습니다: " + value.length());
        }
    }

    public static CampaignName of(String value) {
        return new CampaignName(value);
    }
}
