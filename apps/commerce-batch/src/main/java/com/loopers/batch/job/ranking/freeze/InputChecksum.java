package com.loopers.batch.job.ranking.freeze;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * 입력 checksum. orderId · productId 순 정렬, 필드 사이 TAB · 행 끝 LF, UTC 시각을 마이크로초까지 고정 형식으로 쓴 뒤 SHA-256 (9주차 D-178).
 * 실행 ID · 실행 시각은 넣지 않는다
 */
public final class InputChecksum {

    private static final DateTimeFormatter UTC_MICROS = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'");
    private static final Comparator<PurchaseInput> ORDER = Comparator.comparingLong(PurchaseInput::orderId)
        .thenComparingLong(PurchaseInput::productId);

    private InputChecksum() {}

    public static String of(List<PurchaseInput> inputs) {
        StringBuilder serialized = new StringBuilder();
        inputs.stream().sorted(ORDER).forEach(input -> serialized
            .append(input.orderId()).append('\t')
            .append(input.productId()).append('\t')
            .append(input.quantity()).append('\t')
            .append(input.confirmedAtUtc().format(UTC_MICROS)).append('\n'));
        return sha256(serialized.toString());
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
