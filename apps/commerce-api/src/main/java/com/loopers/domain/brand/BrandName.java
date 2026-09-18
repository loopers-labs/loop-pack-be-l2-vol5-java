package com.loopers.domain.brand;

public record BrandName(String value) {

    private static final int MAX_CODE_POINT_LENGTH = 100;

    public BrandName {
        if (value == null) {
            throw new BrandNameException(BrandNameException.Reason.EMPTY_NAME);
        }
        value = value.strip();
        if (value.isEmpty()) {
            throw new BrandNameException(BrandNameException.Reason.EMPTY_NAME);
        }
        if (value.codePointCount(0, value.length()) > MAX_CODE_POINT_LENGTH) {
            throw new BrandNameException(BrandNameException.Reason.NAME_TOO_LONG);
        }
    }
}
