package com.loopers.domain.ranking;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FrozenRankingInputTest {
    private static final LocalDate MONDAY = LocalDate.of(2026, 8, 17);
    private static final Instant FROZEN_AT = Instant.parse("2026-08-24T00:01:00.123456789Z");
    private static final String FIXTURE_BYTES = "42\t7\t2\t2026-08-17T01:00:00.000000000Z\n"
        + "42\t8\t1\t2026-08-17T01:00:00.000000000Z\n"
        + "43\t7\t3\t2026-08-18T01:00:00.000000000Z\n"
        + "44\t9\t5\t2026-08-19T01:00:00.000000000Z\n";
    private static final String FIXTURE_CHECKSUM = "4a00f7d7a6421c8e457a59012754f53ca6069dc391e14c582fc7e551bab60863";

    @Test
    void keepsAllOrderItemsInCanonicalOrderAndMatchesIndependentFixtureBytesAndHash() throws Exception {
        var fixture = fixture();
        var input = input(List.of(fixture.get(3), fixture.get(1), fixture.get(2), fixture.get(0)));

        assertThat(input.purchases()).containsExactlyElementsOf(fixture);
        assertThat(input.inputCount()).isEqualTo(4L);
        assertThat(input.productCount()).isEqualTo(3L);
        assertThat(input.checksumVersion()).isEqualTo("v1");
        assertThat(FIXTURE_BYTES.getBytes(StandardCharsets.UTF_8)).hasSize(152);
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(FIXTURE_BYTES.getBytes(StandardCharsets.UTF_8)))).isEqualTo(FIXTURE_CHECKSUM);
        assertThat(input.inputChecksum()).isEqualTo(FIXTURE_CHECKSUM);
    }

    @Test
    void preservesNanosecondEdgesInTheFixedNineDigitChecksumFormat() throws Exception {
        var input = input(List.of(
            new RankingPurchase(1, 7, 2, Instant.parse("2026-08-17T00:00:00.000000001Z")),
            new RankingPurchase(2, 7, 3, Instant.parse("2026-08-23T23:59:59.999999999Z"))));
        String bytes = "1\t7\t2\t2026-08-17T00:00:00.000000001Z\n"
            + "2\t7\t3\t2026-08-23T23:59:59.999999999Z\n";
        String checksum = "a988cb545d93daee4d2024179ac8f1df64dc7103d2f1594e84e8a0b10ce5d2a9";

        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(bytes.getBytes(StandardCharsets.UTF_8)))).isEqualTo(checksum);
        assertThat(input.inputChecksum()).isEqualTo(checksum);
        assertThat(input.purchases().get(0).confirmedAt().getNano()).isEqualTo(1);
        assertThat(input.purchases().get(1).confirmedAt().getNano()).isEqualTo(999_999_999);
    }

    @Test
    void treatsEmptyInputAsAValidFrozenSnapshotWithTheZeroByteDigest() {
        var input = input(List.of());

        assertThat(input.inputCount()).isZero();
        assertThat(input.productCount()).isZero();
        assertThat(input.purchases()).isEmpty();
        assertThat(input.inputChecksum()).isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    @Test
    void keepsContentChecksumIndependentOfInputOrderRevisionAndFreezeObservation() {
        var ordered = input(fixture());
        var reversed = new ArrayList<>(fixture());
        java.util.Collections.reverse(reversed);
        var revised = new FrozenRankingInput(new WeeklyRankingKey(MONDAY, 2), FROZEN_AT.plusSeconds(60), reversed);

        assertThat(revised.inputChecksum()).isEqualTo(ordered.inputChecksum());
        assertThat(revised.key().snapshotId()).isNotEqualTo(ordered.key().snapshotId());
        assertThat(revised.frozenAt()).isEqualTo(FROZEN_AT.plusSeconds(60));
    }

    @Test
    void copiesTheSourceListAndExposesOnlyImmutablePurchases() {
        var source = new ArrayList<>(fixture());
        var input = input(source);

        source.clear();

        assertThat(input.purchases()).containsExactlyElementsOf(fixture());
        assertThatThrownBy(() -> input.purchases().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(input.inputChecksum()).isEqualTo(FIXTURE_CHECKSUM);
    }

    @Test
    void rejectsRepeatedOrderProductEvenWhenTheFactsAreEqual() {
        var purchase = fixture().getFirst();

        assertThatThrownBy(() -> input(List.of(purchase, purchase))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> input(List.of(purchase,
            new RankingPurchase(purchase.orderId(), purchase.productId(), 3, purchase.confirmedAt()))))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @MethodSource("outsideWeek")
    void rejectsOutOfPeriodFactsInsteadOfSilentlySkippingThem(String confirmedAt) {
        var purchase = new RankingPurchase(42, 7, 2, Instant.parse(confirmedAt));

        assertThatThrownBy(() -> input(List.of(purchase))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMissingSnapshotFacts() {
        var key = new WeeklyRankingKey(MONDAY, 1);

        assertThatThrownBy(() -> new FrozenRankingInput(null, FROZEN_AT, List.of()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FrozenRankingInput(key, null, List.of()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FrozenRankingInput(key, FROZEN_AT, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> input(Arrays.asList(fixture().getFirst(), null)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private static Stream<Arguments> outsideWeek() {
        return Stream.of(
            Arguments.of("2026-08-16T23:59:59.999999999Z"),
            Arguments.of("2026-08-24T00:00:00Z"));
    }

    private static FrozenRankingInput input(List<RankingPurchase> purchases) {
        return new FrozenRankingInput(new WeeklyRankingKey(MONDAY, 1), FROZEN_AT, purchases);
    }

    private static List<RankingPurchase> fixture() {
        return List.of(
            new RankingPurchase(42, 7, 2, Instant.parse("2026-08-17T01:00:00Z")),
            new RankingPurchase(42, 8, 1, Instant.parse("2026-08-17T01:00:00Z")),
            new RankingPurchase(43, 7, 3, Instant.parse("2026-08-18T01:00:00Z")),
            new RankingPurchase(44, 9, 5, Instant.parse("2026-08-19T01:00:00Z")));
    }
}
