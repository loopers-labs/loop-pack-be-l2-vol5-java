package com.loopers.batch.domain;

import java.time.Instant;

/**
 * 고정 입력의 요약 (W9 D-3). 건수·수량 합은 사람이 읽고, checksum은 "같은 입력인가"를 기계가 판단한다.
 */
public record InputSummary(long count, long quantitySum, String checksum) {

    public static Accumulator accumulator() {
        return new Accumulator();
    }

    public static String line(long orderId, long productId, int quantity, Instant confirmedAt) {
        return "";
    }

    public static final class Accumulator {

        private Accumulator() {}

        public void add(long orderId, long productId, int quantity, Instant confirmedAt) {
        }

        public InputSummary finish() {
            return new InputSummary(0, 0, "");
        }
    }
}
