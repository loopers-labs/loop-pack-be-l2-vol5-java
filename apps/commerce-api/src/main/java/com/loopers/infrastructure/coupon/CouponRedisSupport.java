package com.loopers.infrastructure.coupon;

import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** 쿠폰 Redis 저장소 두 곳이 공유하는 Lua 읽기·ID 입력 검사. */
final class CouponRedisSupport {

    private CouponRedisSupport() {}

    static String readScript(String path) {
        try {
            return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Lua 스크립트를 읽을 수 없습니다. [path = " + path + "]", e);
        }
    }

    static void requireId(Long value, String name) {
        if (value == null || value < 1) {
            throw new IllegalArgumentException(name + " 는 1 이상이어야 합니다. [" + name + " = " + value + "]");
        }
    }
}
