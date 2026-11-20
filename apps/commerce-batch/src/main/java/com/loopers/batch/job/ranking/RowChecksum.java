package com.loopers.batch.job.ranking;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.StringJoiner;

/**
 * 행 묶음의 SHA-256 checksum. 한 행은 값을 TAB으로 잇고 LF로 끝낸다(마지막 행 포함). 행을 넣는 순서는 호출한 쪽이 정한다.
 * <ul>
 *   <li>입력: orderId·productId 오름차순으로 {@code orderId, productId, quantity, confirmedAt}</li>
 *   <li>결과: 순위 순서로 {@code productId, score, rank}</li>
 * </ul>
 * 시각은 {@link #utc(LocalDateTime)} 형식(UTC {@code yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'})으로 넣는다. 실행 ID·실행 시각은 넣지 않는다.
 */
public final class RowChecksum {
    private static final DateTimeFormatter UTC_MICROS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'");

    private final MessageDigest digest;

    public RowChecksum() {
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String utc(LocalDateTime utcTime) {
        return utcTime.format(UTC_MICROS);
    }

    public void addRow(Object... values) {
        StringJoiner line = new StringJoiner("\t", "", "\n");
        for (Object value : values) {
            line.add(String.valueOf(value));
        }
        digest.update(line.toString().getBytes(StandardCharsets.UTF_8));
    }

    public String hex() {
        return HexFormat.of().formatHex(digest.digest());
    }
}
