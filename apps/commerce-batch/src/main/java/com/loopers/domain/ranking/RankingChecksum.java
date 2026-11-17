package com.loopers.domain.ranking;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;

/**
 * 정렬된 행을 TAB 구분·LF 종료·UTF-8 로 직렬화해 SHA-256 에 누적한다(랭킹 설계 3.5).
 * 전체 문자열을 모으지 않는다. 행이 없으면 0바이트의 digest 다.
 */
public final class RankingChecksum {

    private static final DateTimeFormatter UTC_MICROS = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'");

    private final MessageDigest digest;
    private long rowCount;

    public RankingChecksum() {
        try {
            this.digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public void addInputRow(long orderId, long productId, long quantity, LocalDateTime confirmedAtUtc) {
        addRow(Long.toString(orderId), Long.toString(productId), Long.toString(quantity), formatUtc(confirmedAtUtc));
    }

    public long rowCount() {
        return rowCount;
    }

    public String finishHex() {
        return HexFormat.of().formatHex(digest.digest());
    }

    public static String formatUtc(LocalDateTime utcFields) {
        return utcFields.atOffset(ZoneOffset.UTC).format(UTC_MICROS);
    }

    private void addRow(String... fields) {
        digest.update((String.join("\t", fields) + "\n").getBytes(StandardCharsets.UTF_8));
        rowCount++;
    }
}
