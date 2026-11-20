package com.loopers.batch.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 고정 입력의 요약 — 건수·수량 합·checksum (W9 D-3).
 * checksum 기대값은 코드와 따로 셸(printf + shasum -a 256)로 계산했다.
 */
class InputSummaryTest {

    @Test
    void 행이_없으면_0건_합_0이고_checksum은_빈_문자열의_SHA256이다() {
        // given
        InputSummary.Accumulator accumulator = InputSummary.accumulator();

        // when
        InputSummary summary = accumulator.finish();

        // then
        assertThat(summary).isEqualTo(new InputSummary(0, 0, "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"));
    }

    @Test
    void 발제_fixture_4행이면_4건_합_11이고_정해진_checksum이다() {
        // given: 주문 ID → 상품 ID 순
        InputSummary.Accumulator accumulator = InputSummary.accumulator();
        accumulator.add(42L, 7L, 2, Instant.parse("2026-08-17T01:00:00Z"));
        accumulator.add(42L, 8L, 1, Instant.parse("2026-08-17T01:00:00Z"));
        accumulator.add(43L, 7L, 3, Instant.parse("2026-08-18T01:00:00Z"));
        accumulator.add(44L, 9L, 5, Instant.parse("2026-08-19T01:00:00Z"));

        // when
        InputSummary summary = accumulator.finish();

        // then
        assertThat(summary).isEqualTo(new InputSummary(4, 11, "3e90276ec1f9123f308cd18d155e1dde6bf811ef3aa741105ff5c766c357a298"));
    }

    @Test
    void 한_행은_주문_상품_수량_UTC_마이크로초_시각을_TAB으로_나누고_LF로_끝낸다() {
        // given
        Instant confirmedAt = Instant.parse("2026-08-17T01:00:00Z");

        // when
        String line = InputSummary.line(42L, 7L, 2, confirmedAt);

        // then
        assertThat(line).isEqualTo("42\t7\t2\t2026-08-17T01:00:00.000000Z\n");
    }
}
