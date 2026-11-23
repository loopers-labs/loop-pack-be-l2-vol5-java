package com.loopers.batch.job.ranking;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 회차 결과의 checksum. 계산 순위 오름차순으로 {@code productId TAB score TAB rank} + LF를 이어 SHA-256을 계산한다.
 * 마지막 줄 뒤에도 LF를 붙이고 16진수 소문자로 낸다. 0건이면 빈 문자열의 SHA-256이다.
 */
public final class RankingChecksum {

    private final MessageDigest digest = sha256();
    private int lastRank;

    public RankingChecksum add(long productId, long score, int rank) {
        if (rank <= lastRank) {
            throw new IllegalArgumentException("결과는 계산 순위 오름차순이어야 한다. rank=" + rank);
        }
        lastRank = rank;
        digest.update((productId + "\t" + score + "\t" + rank + "\n").getBytes(StandardCharsets.UTF_8));
        return this;
    }

    // 지금까지의 상태를 복사해 계산하므로 hex()를 부른 뒤에도 이어서 더할 수 있다.
    public String hex() {
        try {
            return HexFormat.of().formatHex(((MessageDigest) digest.clone()).digest());
        } catch (CloneNotSupportedException e) {
            throw new IllegalStateException("SHA-256 상태를 복사할 수 없다.", e);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 쓸 수 없다.", e);
        }
    }
}
