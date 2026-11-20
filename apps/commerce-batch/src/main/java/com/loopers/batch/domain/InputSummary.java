package com.loopers.batch.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;

/**
 * 고정 입력의 요약 (W9 D-3). 건수·수량 합은 사람이 읽고, checksum은 "같은 입력인가"를 기계가 판단한다.
 * checksum = 행마다 {@link #line}을 이어 붙인 글자의 SHA-256(hex). 행은 주문 ID → 상품 ID 순으로 넣어야 한다.
 */
public record InputSummary(long count, long quantitySum, String checksum) {

    private static final DateTimeFormatter UTC_MICROS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC);

    public static Accumulator accumulator() {
        return new Accumulator();
    }

    /**
     * 주문 ID ⇥ 상품 ID ⇥ 수량 ⇥ 확정 시각(UTC, 마이크로초 6자리, Z) ↵
     */
    public static String line(long orderId, long productId, int quantity, Instant confirmedAt) {
        return orderId + "\t" + productId + "\t" + quantity + "\t" + UTC_MICROS.format(confirmedAt) + "\n";
    }

    /**
     * 행을 하나씩 받아 누적한다. 100만 행이어도 행을 모아 두지 않는다.
     */
    public static final class Accumulator {

        private final MessageDigest digest;
        private long count;
        private long quantitySum;

        private Accumulator() {
            try {
                this.digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256을 쓸 수 없습니다.", e);
            }
        }

        public void add(long orderId, long productId, int quantity, Instant confirmedAt) {
            digest.update(line(orderId, productId, quantity, confirmedAt).getBytes(StandardCharsets.UTF_8));
            count++;
            quantitySum += quantity;
        }

        public InputSummary finish() {
            return new InputSummary(count, quantitySum, HexFormat.of().formatHex(digest.digest()));
        }
    }
}
