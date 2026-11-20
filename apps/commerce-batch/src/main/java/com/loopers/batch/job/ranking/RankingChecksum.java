package com.loopers.batch.job.ranking;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * snapshot 입력·완성 결과의 내용 checksum (설계 10-4, DR-69). SHA-256 소문자 hex.
 * 행마다 필드를 TAB 으로 잇고 LF 로 끝낸다 (마지막 행도 LF). 빈 입력은 빈 문자열의 해시다.
 * 시각은 UTC, 마이크로초 6자리 고정 (`2026-08-17T01:00:00.000000Z`). 실행 ID·실행 시각은 넣지 않는다.
 */
public final class RankingChecksum {
    private static final DateTimeFormatter UTC_MICROS = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'");

    private RankingChecksum() {
    }

    /** snapshot 입력 한 행. confirmedAtUtc 는 DB 의 UTC datetime 값 그대로. */
    public record InputLine(long orderId, long productId, int quantity, LocalDateTime confirmedAtUtc) {
    }

    /** 완성 ranking 한 행. */
    public record ResultLine(long productId, long score, int rank) {
    }

    /** orderId, productId 순으로 정렬한 `orderId TAB productId TAB quantity TAB confirmedAt LF`. */
    public static String inputText(List<InputLine> lines) {
        StringBuilder text = new StringBuilder();
        lines.stream()
            .sorted(Comparator.comparingLong(InputLine::orderId).thenComparingLong(InputLine::productId))
            .forEach(line -> text.append(line.orderId()).append('\t')
                .append(line.productId()).append('\t')
                .append(line.quantity()).append('\t')
                .append(UTC_MICROS.format(line.confirmedAtUtc())).append('\n'));
        return text.toString();
    }

    /** rank 순으로 정렬한 `productId TAB score TAB rank LF`. */
    public static String resultText(List<ResultLine> lines) {
        StringBuilder text = new StringBuilder();
        lines.stream()
            .sorted(Comparator.comparingInt(ResultLine::rank))
            .forEach(line -> text.append(line.productId()).append('\t')
                .append(line.score()).append('\t')
                .append(line.rank()).append('\n'));
        return text.toString();
    }

    public static String ofInputs(List<InputLine> lines) {
        return sha256(inputText(lines));
    }

    public static String ofResults(List<ResultLine> lines) {
        return sha256(resultText(lines));
    }

    public static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
