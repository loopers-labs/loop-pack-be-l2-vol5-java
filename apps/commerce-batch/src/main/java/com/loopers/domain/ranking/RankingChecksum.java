package com.loopers.domain.ranking;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * 입력·결과 checksum 규칙 (설계 D13). 직렬화와 해시를 분리해 테스트가 문자열을 직접 단언할 수 있게 한다.
 * 실행 ID·실행 시각·snapshotId 는 넣지 않는다. 빈 목록의 checksum 은 빈 문자열의 SHA-256 이다.
 */
public final class RankingChecksum {

    /** confirmedAt 은 UTC 고정 6자리. DB DATETIME(6) 과 정밀도 1:1, 길이 고정이라 기대값을 손으로 계산하기 쉽다. */
    private static final DateTimeFormatter CONFIRMED_AT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'");

    private static final Comparator<InputRow> INPUT_ORDER =
        Comparator.comparingLong(InputRow::orderId).thenComparingLong(InputRow::productId);
    private static final Comparator<ResultRow> RESULT_ORDER = Comparator.comparingInt(ResultRow::ranking);

    private RankingChecksum() {}

    /** 입력 행. confirmedAtUtc 는 UTC 벽시계(LocalDateTime). */
    public record InputRow(long orderId, long productId, int quantity, LocalDateTime confirmedAtUtc) {}

    /** 결과 행. */
    public record ResultRow(long productId, long score, int ranking) {}

    /** (orderId, productId) 오름차순, 행마다 {@code orderId TAB productId TAB quantity TAB confirmedAt} + LF. */
    public static String serializeInputs(List<InputRow> rows) {
        StringBuilder sb = new StringBuilder();
        rows.stream().sorted(INPUT_ORDER).forEach(row -> sb
            .append(row.orderId()).append('\t')
            .append(row.productId()).append('\t')
            .append(row.quantity()).append('\t')
            .append(CONFIRMED_AT.format(row.confirmedAtUtc())).append('\n'));
        return sb.toString();
    }

    /** ranking 오름차순, 행마다 {@code productId TAB score TAB ranking} + LF. */
    public static String serializeResults(List<ResultRow> rows) {
        StringBuilder sb = new StringBuilder();
        rows.stream().sorted(RESULT_ORDER).forEach(row -> sb
            .append(row.productId()).append('\t')
            .append(row.score()).append('\t')
            .append(row.ranking()).append('\n'));
        return sb.toString();
    }

    public static String inputChecksum(List<InputRow> rows) {
        return sha256Hex(serializeInputs(rows));
    }

    public static String resultChecksum(List<ResultRow> rows) {
        return sha256Hex(serializeResults(rows));
    }

    /** SHA-256, 소문자 hex 64자. */
    public static String sha256Hex(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 을 사용할 수 없습니다.", e);
        }
    }
}
