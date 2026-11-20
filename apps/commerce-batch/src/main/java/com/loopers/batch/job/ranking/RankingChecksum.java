package com.loopers.batch.job.ranking;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;

/**
 * 입력·결과 checksum을 줄 단위로 쌓아 SHA-256 hex로 만든다 (docs/week9/design.md 8절).
 * 필드 사이는 TAB, 모든 줄 끝(마지막 줄 포함)은 LF다. 실행 ID·실행 시각은 넣지 않는다.
 */
public final class RankingChecksum {

    /** UTC 시각을 마이크로초 6자리까지 고정 길이로 쓴다. */
    public static final DateTimeFormatter UTC_MICROS = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'");

    private final MessageDigest digest;

    public RankingChecksum() {
        try {
            this.digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 입력 한 줄: orderId, productId, quantity, confirmedAt(UTC). */
    public void addInput(long orderId, long productId, int quantity, LocalDateTime confirmedAtUtc) {
        addLine(orderId + "\t" + productId + "\t" + quantity + "\t" + confirmedAtUtc.format(UTC_MICROS));
    }

    /** 결과 한 줄: productId, score, rank. */
    public void addResult(long productId, long score, int rank) {
        addLine(productId + "\t" + score + "\t" + rank);
    }

    public String hex() {
        return HexFormat.of().formatHex(digest.digest());
    }

    private void addLine(String line) {
        digest.update((line + "\n").getBytes(StandardCharsets.UTF_8));
    }
}
