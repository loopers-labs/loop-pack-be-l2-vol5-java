package com.loopers.domain.payment;

import java.util.regex.Pattern;

public record CardNumber(String value) {

    private static final Pattern FORMAT = Pattern.compile("\\d{4}-\\d{4}-\\d{4}-\\d{4}");

    public CardNumber {
        if (value == null || !FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("카드 번호는 0000-0000-0000-0000 형식이어야 합니다");
        }
    }

    public static CardNumber of(String value) {
        return new CardNumber(value);
    }

    @Override
    public String toString() {
        return "CardNumber[****]";
    }
}
