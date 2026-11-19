package com.loopers.domain.ranking;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/** 필드는 TAB, 행은 LF(마지막 행 포함)로 직렬화한 SHA-256. 실행 ID·실행 시각은 넣지 않는다. */
public final class SnapshotChecksum {

    private static final DateTimeFormatter UTC_MICROS =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC);

    private SnapshotChecksum() {}

    public static String ofInput(List<SnapshotInputLine> lines) {
        StringBuilder text = new StringBuilder();
        lines.stream()
            .sorted(Comparator.comparing(SnapshotInputLine::orderId).thenComparing(SnapshotInputLine::productId))
            .forEach(line -> text.append(line.orderId()).append('\t')
                .append(line.productId()).append('\t')
                .append(line.quantity()).append('\t')
                .append(UTC_MICROS.format(line.confirmedAt().toInstant())).append('\n'));
        return sha256(text.toString());
    }

    public static String ofResult(List<RankedProduct> ranked) {
        StringBuilder text = new StringBuilder();
        ranked.stream()
            .sorted(Comparator.comparingInt(RankedProduct::rank))
            .forEach(product -> text.append(product.productId()).append('\t')
                .append(product.score()).append('\t')
                .append(product.rank()).append('\n'));
        return sha256(text.toString());
    }

    private static String sha256(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
