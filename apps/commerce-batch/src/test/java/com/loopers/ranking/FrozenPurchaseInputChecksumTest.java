package com.loopers.ranking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.loopers.domain.ranking.model.RankingPeriod;
import com.loopers.domain.ranking.model.SnapshotIntegrityException;
import com.loopers.domain.ranking.policy.FrozenPurchaseInputChecksum;
import java.time.Instant;
import java.time.LocalDate;
import java.util.TimeZone;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class FrozenPurchaseInputChecksumTest {
    private static final RankingPeriod PERIOD = new RankingPeriod(LocalDate.of(2026, 8, 17));

    @Test
    @DisplayName("수작업으로 정한 다품목 4행의 고정 UTF-8 직렬화와 SHA-256이 일치한다")
    void matchesFourRowCanonicalFixture() {
        var checksum = new FrozenPurchaseInputChecksum(PERIOD);
        checksum.accept(42, 7, 2, Instant.parse("2026-08-17T01:00:00Z"));
        checksum.accept(42, 8, 1, Instant.parse("2026-08-17T01:00:00Z"));
        checksum.accept(43, 7, 3, Instant.parse("2026-08-18T01:00:00Z"));
        checksum.accept(44, 9, 5, Instant.parse("2026-08-19T01:00:00Z"));

        var result = checksum.finish();
        assertThat(result.count()).isEqualTo(4);
        assertThat(result.sha256()).isEqualTo("3e90276ec1f9123f308cd18d155e1dde6bf811ef3aa741105ff5c766c357a298");
    }

    @ParameterizedTest
    @ValueSource(strings = {"UTC", "Asia/Seoul", "America/New_York"})
    @DisplayName("JVM 기본 시간대와 무관하게 UTC 마이크로초 6자리와 마지막 LF를 보존한다")
    void preservesMicrosecondsIndependentlyOfDefaultTimezone(String timezone) {
        var previous = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(timezone));
            var checksum = new FrozenPurchaseInputChecksum(PERIOD);
            checksum.accept(42, 7, 2, Instant.parse("2026-08-17T01:00:00.123456Z"));

            assertThat(checksum.finish().sha256())
                .isEqualTo("fb59d069f86292b30305f062e0ecc8abd58b038e869ceae81a7a0efc31392a36");
        } finally {
            TimeZone.setDefault(previous);
        }
    }

    @Test
    @DisplayName("빈 입력은 0건과 빈 바이트열의 SHA-256으로 고정한다")
    void hashesEmptyInput() {
        var result = new FrozenPurchaseInputChecksum(PERIOD).finish();

        assertThat(result.count()).isZero();
        assertThat(result.sha256()).isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    @ParameterizedTest
    @CsvSource({"0,7,1", "42,0,1", "42,7,0"})
    @DisplayName("고정 입력의 주문·상품·수량이 양수가 아니면 정상 checksum으로 숨기지 않는다")
    void rejectsInvalidFacts(long orderId, long productId, int quantity) {
        var checksum = new FrozenPurchaseInputChecksum(PERIOD);

        assertThatThrownBy(() -> checksum.accept(orderId, productId, quantity,
            Instant.parse("2026-08-17T01:00:00Z"))).isInstanceOf(SnapshotIntegrityException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-08-16T23:59:59.999999Z", "2026-08-24T00:00:00Z", "2026-08-17T01:00:00.000000001Z"})
    @DisplayName("기간 밖 또는 마이크로초 계약을 위반한 입력은 거절한다")
    void rejectsInvalidTimestamps(String timestamp) {
        assertThatThrownBy(() -> new FrozenPurchaseInputChecksum(PERIOD).accept(42, 7, 1, Instant.parse(timestamp)))
            .isInstanceOf(SnapshotIntegrityException.class);
    }

    @ParameterizedTest
    @ValueSource(longs = {7, 8})
    @DisplayName("동일·역행하는 주문 상품 키는 정렬 계약 위반으로 거절한다")
    void rejectsDuplicateOrUnorderedInputs(long productId) {
        var checksum = new FrozenPurchaseInputChecksum(PERIOD);
        checksum.accept(42, 8, 1, Instant.parse("2026-08-17T01:00:00Z"));

        assertThatThrownBy(() -> checksum.accept(42, productId, 1, Instant.parse("2026-08-17T01:00:00Z")))
            .isInstanceOf(SnapshotIntegrityException.class);
    }
}
