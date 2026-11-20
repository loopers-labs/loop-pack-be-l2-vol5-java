package com.loopers.infrastructure.persistence.ranking;

import com.loopers.domain.ranking.model.vo.InputFingerprint;
import com.loopers.domain.ranking.model.vo.RankingPeriod;
import com.loopers.domain.ranking.policy.FrozenPurchaseInputPolicy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.HexFormat;
import java.util.Objects;

public final class PurchaseInputFingerprintAccumulator {
    private static final DateTimeFormatter UTC_MICROS = new DateTimeFormatterBuilder().appendInstant(6).toFormatter();
    private final RankingPeriod period;
    private final MessageDigest digest;
    private long count;
    private FrozenPurchaseInputPolicy.Row previous;
    private InputFingerprint completed;

    public PurchaseInputFingerprintAccumulator(RankingPeriod period) {
        this.period = Objects.requireNonNull(period);
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 provider가 필요합니다.", exception);
        }
    }

    public void accept(long orderId, long productId, int quantity, Instant confirmedAt) {
        if (completed != null) {
            throw new IllegalStateException("완료한 입력 checksum에 행을 추가할 수 없습니다.");
        }
        var current = new FrozenPurchaseInputPolicy.Row(orderId, productId, quantity, confirmedAt);
        FrozenPurchaseInputPolicy.requireValid(period, current, previous);
        long next = Math.addExact(count, 1);
        String row = orderId + "\t" + productId + "\t" + quantity + "\t" + UTC_MICROS.format(confirmedAt) + "\n";
        digest.update(row.getBytes(StandardCharsets.UTF_8));
        count = next;
        previous = current;
    }

    public InputFingerprint finish() {
        if (completed == null) {
            completed = new InputFingerprint(count, HexFormat.of().formatHex(digest.digest()));
        }
        return completed;
    }
}
