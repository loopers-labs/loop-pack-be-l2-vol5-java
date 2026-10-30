package com.loopers.contract;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

public final class OrderConfirmedContract {

    private static final String EXAMPLE = "/contracts/order-confirmed-v1.json";

    private OrderConfirmedContract() {
    }

    public static String example() {
        try (InputStream in = OrderConfirmedContract.class.getResourceAsStream(EXAMPLE)) {
            if (in == null) {
                throw new IllegalStateException("계약 예시가 없습니다: " + EXAMPLE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
