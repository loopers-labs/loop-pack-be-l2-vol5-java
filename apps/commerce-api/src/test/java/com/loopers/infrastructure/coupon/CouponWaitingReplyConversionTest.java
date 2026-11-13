package com.loopers.infrastructure.coupon;

import com.loopers.domain.coupon.CouponAdmissionRepository.AdmissionProgress;
import com.loopers.domain.coupon.CouponAdmissionRepository.FinishOutcome;
import com.loopers.domain.coupon.CouponAdmissionRepository.RegistrationOutcome;
import com.loopers.domain.coupon.CouponAdmissionRepository.StartOutcome;
import com.loopers.domain.coupon.CouponWaitingSnapshot;
import com.loopers.domain.coupon.CouponWaitingState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * Lua 응답 변환의 잘못된 응답 분기(대역 입력). 실제 Lua 의 동작 근거는 CouponWaitingRedisIntegrationTest 이며 이 검사로 대신하지 않는다.
 * 정상 Lua 가 만들지 않는 응답도 성공·미발견으로 바꾸지 않고 IllegalStateException 으로 드러나는지만 본다.
 */
@DisplayName("Lua 응답이 길이·원소 타입·결과명·JSON·숫자·상태별 필수값 계약과 다르면 결과로 해석하지 않는다.")
class CouponWaitingReplyConversionTest {

    private static final Long CAMPAIGN = 42L;
    private static final String WAITING = "{\"waitingId\":\"w-1\",\"status\":\"WAITING\",\"sequence\":\"3\"}";
    private static final String READY = "{\"waitingId\":\"w-1\",\"status\":\"READY\",\"sequence\":\"3\",\"expiresAtMillis\":\"1763000000123\"}";
    /** 누락(null)·JSON 숫자·null·빈/공백·소수·지수·선행 0·0·음수·2^53 이상(long 범위 안 포함)·long 초과. */
    private static final List<String> INVALID_EXACT_INTEGERS = Arrays.asList(
        null, "3", "null", "\"\"", "\" \"", "\"1.5\"", "\"1e3\"", "\"03\"", "\"0\"", "\"-1\"",
        "\"9007199254740992\"", "\"9223372036854775807\"", "\"99999999999999999999\""
    );

    private static List<Object> reply(Object... parts) {
        return Arrays.asList(parts);
    }

    @DisplayName("정상 응답은 상태별 필드만 채운 snapshot·결과로 바꾼다.")
    @Test
    void convertsValidReplies() {
        assertAll(
            () -> assertThat(CouponAdmissionRedisRepository.toRegistration(CAMPAIGN, reply("CREATED", WAITING, "2")).snapshot())
                .isEqualTo(new CouponWaitingSnapshot(CAMPAIGN, "w-1", CouponWaitingState.WAITING, 2L, null, null)),
            () -> assertThat(CouponAdmissionRedisRepository.toFound(CAMPAIGN, reply("FOUND", READY, "")).orElseThrow())
                .isEqualTo(new CouponWaitingSnapshot(CAMPAIGN, "w-1", CouponWaitingState.READY, null, Instant.ofEpochMilli(1763000000123L), null)),
            () -> assertThat(CouponAdmissionRedisRepository.toFound(CAMPAIGN, reply("NOT_FOUND", "", ""))).isEmpty(),
            () -> assertThat(CouponAdmissionRedisRepository.toRegistration(CAMPAIGN, reply("SOLD_OUT", "", "")).outcome()).isEqualTo(RegistrationOutcome.SOLD_OUT),
            () -> assertThat(CouponAdmissionRedisRepository.toRegistration(CAMPAIGN, reply("INVARIANT_ERROR", "reason", "")).outcome())
                .isEqualTo(RegistrationOutcome.INVARIANT_ERROR),
            () -> assertThat(CouponAdmissionRedisRepository.toProgress(reply("OK", "1", "2"), 3)).isEqualTo(new AdmissionProgress(1, 2)),
            () -> assertThat(CouponAdmissionRedisRepository.toStart(CAMPAIGN, reply("STARTED", "", "")).outcome()).isEqualTo(StartOutcome.STARTED),
            () -> assertThat(CouponAdmissionRedisRepository.toFinish(reply("UNCHANGED"))).isEqualTo(FinishOutcome.UNCHANGED)
        );
    }

    @DisplayName("null·목록 아님·길이 불일치·문자열 아닌 원소·알 수 없는 결과명은 다섯 변환 모두 오류다.")
    @Test
    void rejectsMalformedEnvelope() {
        List<Object> bad = List.of(reply(), reply("OK", "1"), reply("OK", 1L, "2"), reply("UNKNOWN", "", ""));
        for (Object candidate : Arrays.asList(null, "OK", bad.get(0), bad.get(1), bad.get(2), bad.get(3))) {
            assertAll(
                () -> assertThatThrownBy(() -> CouponAdmissionRedisRepository.toRegistration(CAMPAIGN, candidate)).isInstanceOf(IllegalStateException.class),
                () -> assertThatThrownBy(() -> CouponAdmissionRedisRepository.toFound(CAMPAIGN, candidate)).isInstanceOf(IllegalStateException.class),
                () -> assertThatThrownBy(() -> CouponAdmissionRedisRepository.toProgress(candidate, 3)).isInstanceOf(IllegalStateException.class),
                () -> assertThatThrownBy(() -> CouponAdmissionRedisRepository.toStart(CAMPAIGN, candidate)).isInstanceOf(IllegalStateException.class),
                () -> assertThatThrownBy(() -> CouponAdmissionRedisRepository.toFinish(candidate)).isInstanceOf(IllegalStateException.class)
            );
        }
        assertThatThrownBy(() -> CouponAdmissionRedisRepository.toFinish(reply("APPLIED", ""))).isInstanceOf(IllegalStateException.class);
    }

    @DisplayName("항목 JSON·상태·position·expiresAt 이 계약과 다르면 오류다.")
    @Test
    void rejectsInvalidSnapshot() {
        List<List<Object>> invalid = List.of(
            reply("CREATED", "not-json", "1"),
            reply("CREATED", "[]", "1"),
            reply("CREATED", "{\"status\":\"WAITING\",\"sequence\":\"3\"}", "1"),
            reply("CREATED", "{\"waitingId\":\"w\",\"status\":\"UNKNOWN\",\"sequence\":\"3\"}", "1"),
            reply("CREATED", "{\"waitingId\":1,\"status\":\"WAITING\",\"sequence\":\"3\"}", "1"),
            reply("CREATED", WAITING, ""),
            reply("CREATED", WAITING, "0"),
            reply("CREATED", WAITING, "01"),
            reply("CREATED", WAITING, "-1"),
            reply("CREATED", WAITING, "99999999999999999999"),
            reply("CREATED", READY, ""),
            reply("EXISTING", READY, "1"),
            reply("EXISTING", "{\"waitingId\":\"w\",\"status\":\"READY\",\"sequence\":\"3\"}", ""),
            reply("EXISTING", "{\"waitingId\":\"w\",\"status\":\"READY\",\"sequence\":\"3\",\"expiresAtMillis\":\"x\"}", ""),
            reply("EXISTING", "{\"waitingId\":\"w\",\"status\":\"FAILED\",\"sequence\":\"3\"}", ""),
            reply("SOLD_OUT", WAITING, "")
        );
        for (List<Object> candidate : invalid) {
            assertThatThrownBy(() -> CouponAdmissionRedisRepository.toRegistration(CAMPAIGN, candidate))
                .as(candidate.toString()).isInstanceOf(IllegalStateException.class);
        }
    }

    @DisplayName("find 의 INVARIANT_ERROR·NOT_FOUND 잔여값, start 의 상태 불일치, 회수·승격 수 형식/범위 오류는 오류다.")
    @Test
    void rejectsOperationSpecificMismatch() {
        assertAll(
            () -> assertThatThrownBy(() -> CouponAdmissionRedisRepository.toFound(CAMPAIGN, reply("INVARIANT_ERROR", "reason", "")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("INVARIANT_ERROR"),
            () -> assertThatThrownBy(() -> CouponAdmissionRedisRepository.toFound(CAMPAIGN, reply("NOT_FOUND", WAITING, "1")))
                .isInstanceOf(IllegalStateException.class),
            () -> assertThatThrownBy(() -> CouponAdmissionRedisRepository.toStart(CAMPAIGN, reply("STARTED", WAITING, "")))
                .isInstanceOf(IllegalStateException.class),
            () -> assertThatThrownBy(() -> CouponAdmissionRedisRepository.toStart(CAMPAIGN,
                reply("PROCESSING", "{\"waitingId\":\"w\",\"status\":\"ISSUED\",\"sequence\":\"3\"}", ""))).isInstanceOf(IllegalStateException.class),
            () -> assertThatThrownBy(() -> CouponAdmissionRedisRepository.toStart(CAMPAIGN, reply("ISSUED", "", "")))
                .isInstanceOf(IllegalStateException.class),
            () -> assertThatThrownBy(() -> CouponAdmissionRedisRepository.toProgress(reply("OK", "-1", "0"), 3)).isInstanceOf(IllegalStateException.class),
            () -> assertThatThrownBy(() -> CouponAdmissionRedisRepository.toProgress(reply("OK", "0", "x"), 3)).isInstanceOf(IllegalStateException.class),
            () -> assertThatThrownBy(() -> CouponAdmissionRedisRepository.toProgress(reply("OK", "0", "4"), 3)).isInstanceOf(IllegalStateException.class),
            () -> assertThatThrownBy(() -> CouponAdmissionRedisRepository.toProgress(reply("INVARIANT_ERROR", "reason", ""), 3))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("INVARIANT_ERROR")
        );
    }

    /** 항목 JSON. sequence·expiresAtMillis 는 JSON 값 조각 그대로 넣고, null 이면 필드를 뺀다. */
    private static String entry(String status, String sequence, String expiresAtMillis) {
        StringBuilder json = new StringBuilder("{\"waitingId\":\"w-1\",\"status\":\"").append(status).append('"');
        if (sequence != null) {
            json.append(",\"sequence\":").append(sequence);
        }
        if (expiresAtMillis != null) {
            json.append(",\"expiresAtMillis\":").append(expiresAtMillis);
        }
        return json.append('}').toString();
    }

    @DisplayName("sequence 는 모든 항목에 필수인 1~2^53-1 의 십진 문자열이다. 아니면 오류다.")
    @Test
    void rejectsInvalidSequence() {
        for (String sequence : INVALID_EXACT_INTEGERS) {
            String json = entry("WAITING", sequence, null);
            assertThatThrownBy(() -> CouponAdmissionRedisRepository.toRegistration(CAMPAIGN, reply("CREATED", json, "1")))
                .as(json).isInstanceOf(IllegalStateException.class).hasMessageContaining("sequence");
        }
    }

    @DisplayName("READY·PROCESSING 의 expiresAtMillis 는 필수인 1~2^53-1 의 십진 문자열이다. 아니면 오류다.")
    @Test
    void requiresExactExpiryForReadyAndProcessing() {
        for (String expiresAtMillis : INVALID_EXACT_INTEGERS) {
            String ready = entry("READY", "\"3\"", expiresAtMillis);
            String processing = entry("PROCESSING", "\"3\"", expiresAtMillis);
            assertAll(
                () -> assertThatThrownBy(() -> CouponAdmissionRedisRepository.toFound(CAMPAIGN, reply("FOUND", ready, "")))
                    .as(ready).isInstanceOf(IllegalStateException.class).hasMessageContaining("expiresAtMillis"),
                () -> assertThatThrownBy(() -> CouponAdmissionRedisRepository.toStart(CAMPAIGN, reply("PROCESSING", processing, "")))
                    .as(processing).isInstanceOf(IllegalStateException.class).hasMessageContaining("expiresAtMillis")
            );
        }
    }

    @DisplayName("그 밖의 상태는 expiresAtMillis 가 없어도 되지만, 있으면 같은 형식이어야 한다(명시 null 포함 거절).")
    @Test
    void validatesOptionalExpiryOfOtherStates() {
        for (String expiresAtMillis : INVALID_EXACT_INTEGERS.subList(1, INVALID_EXACT_INTEGERS.size())) {
            String expired = entry("EXPIRED", "\"3\"", expiresAtMillis);
            String issued = entry("ISSUED", "\"3\"", expiresAtMillis);
            assertAll(
                () -> assertThatThrownBy(() -> CouponAdmissionRedisRepository.toFound(CAMPAIGN, reply("FOUND", expired, "")))
                    .as(expired).isInstanceOf(IllegalStateException.class).hasMessageContaining("expiresAtMillis"),
                () -> assertThatThrownBy(() -> CouponAdmissionRedisRepository.toStart(CAMPAIGN, reply("ISSUED", issued, "")))
                    .as(issued).isInstanceOf(IllegalStateException.class).hasMessageContaining("expiresAtMillis")
            );
        }
    }

    @DisplayName("2^53-1 경계는 받아들이고, 기한은 검증해도 응답 expiresAt 은 READY 에만 채운다.")
    @Test
    void acceptsExactBoundaryAndExposesExpiryOnlyForReady() {
        String max = "\"9007199254740991\"";
        assertAll(
            () -> assertThat(CouponAdmissionRedisRepository.toRegistration(CAMPAIGN, reply("CREATED", entry("WAITING", max, null), "1")).snapshot())
                .isEqualTo(new CouponWaitingSnapshot(CAMPAIGN, "w-1", CouponWaitingState.WAITING, 1L, null, null)),
            () -> assertThat(CouponAdmissionRedisRepository.toFound(CAMPAIGN, reply("FOUND", entry("READY", "\"3\"", max), "")).orElseThrow().expiresAt())
                .isEqualTo(Instant.ofEpochMilli(9_007_199_254_740_991L)),
            () -> assertThat(CouponAdmissionRedisRepository.toStart(CAMPAIGN, reply("PROCESSING", entry("PROCESSING", "\"3\"", "\"1763000000123\""), "")).snapshot())
                .isEqualTo(new CouponWaitingSnapshot(CAMPAIGN, "w-1", CouponWaitingState.PROCESSING, null, null, null)),
            () -> assertThat(CouponAdmissionRedisRepository.toFound(CAMPAIGN, reply("FOUND", entry("EXPIRED", "\"3\"", "\"1763000000123\""), "")).orElseThrow())
                .isEqualTo(new CouponWaitingSnapshot(CAMPAIGN, "w-1", CouponWaitingState.EXPIRED, null, null, null)),
            () -> assertThat(CouponAdmissionRedisRepository.toFound(CAMPAIGN, reply("FOUND", entry("SOLD_OUT", "\"3\"", "\"1763000000123\""), "")).orElseThrow())
                .isEqualTo(new CouponWaitingSnapshot(CAMPAIGN, "w-1", CouponWaitingState.SOLD_OUT, null, null, null)),
            () -> assertThat(CouponAdmissionRedisRepository.toStart(CAMPAIGN, reply("ISSUED", entry("ISSUED", "\"3\"", null), "")).snapshot())
                .isEqualTo(new CouponWaitingSnapshot(CAMPAIGN, "w-1", CouponWaitingState.ISSUED, null, null, null))
        );
    }
}
