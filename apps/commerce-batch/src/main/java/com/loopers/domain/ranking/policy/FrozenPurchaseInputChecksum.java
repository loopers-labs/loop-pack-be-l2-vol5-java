package com.loopers.domain.ranking.policy;

import com.loopers.domain.ranking.model.InputFingerprint;
import com.loopers.domain.ranking.model.RankingPeriod;
import com.loopers.domain.ranking.model.SnapshotIntegrityException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.HexFormat;
import java.util.Objects;

public final class FrozenPurchaseInputChecksum {
    public static final int CONTRACT_VERSION = 1;
    private static final DateTimeFormatter UTC_MICROS = new DateTimeFormatterBuilder().appendInstant(6).toFormatter();
    private final RankingPeriod period;
    private final MessageDigest digest;
    private long count;
    private long previousOrderId;
    private long previousProductId;
    private InputFingerprint completed;

    public FrozenPurchaseInputChecksum(RankingPeriod period) {
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
        if (orderId <= 0 || productId <= 0 || quantity <= 0 || !period.contains(confirmedAt)
            || confirmedAt.getNano() % 1000 != 0) {
            throw new SnapshotIntegrityException("고정 입력의 식별자·수량·UTC 기간·마이크로초 계약을 위반했습니다.");
        }
        if (count > 0 && (orderId < previousOrderId || (orderId == previousOrderId && productId <= previousProductId))) {
            throw new SnapshotIntegrityException("고정 입력은 중복 없이 orderId·productId 오름차순이어야 합니다.");
        }
        String row = orderId + "\t" + productId + "\t" + quantity + "\t" + UTC_MICROS.format(confirmedAt) + "\n";
        digest.update(row.getBytes(StandardCharsets.UTF_8));
        count = Math.addExact(count, 1);
        previousOrderId = orderId;
        previousProductId = productId;
    }

    public InputFingerprint finish() {
        if (completed == null) {
            completed = new InputFingerprint(count, HexFormat.of().formatHex(digest.digest()));
        }
        return completed;
    }
}
