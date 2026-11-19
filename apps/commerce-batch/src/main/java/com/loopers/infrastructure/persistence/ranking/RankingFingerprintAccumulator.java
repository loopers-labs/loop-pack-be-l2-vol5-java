package com.loopers.infrastructure.persistence.ranking;

import com.loopers.domain.ranking.model.vo.RankingResultFingerprint;
import com.loopers.domain.ranking.policy.WeeklyRankingOrderPolicy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class RankingFingerprintAccumulator {
    private final MessageDigest digest;
    private long count;
    private WeeklyRankingOrderPolicy.Row previous;
    private RankingResultFingerprint completed;

    public RankingFingerprintAccumulator() {
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 provider가 필요합니다.", exception);
        }
    }

    public void accept(long productId, long score, long rank) {
        if (completed != null) {
            throw new IllegalStateException("완료한 결과 checksum에 행을 추가할 수 없습니다.");
        }
        var current = new WeeklyRankingOrderPolicy.Row(productId, score, rank);
        WeeklyRankingOrderPolicy.requireValid(current, previous);
        long next = Math.addExact(count, 1);
        digest.update((productId + "\t" + score + "\t" + rank + "\n").getBytes(StandardCharsets.UTF_8));
        count = next;
        previous = current;
    }

    public RankingResultFingerprint finish() {
        if (completed == null) {
            completed = new RankingResultFingerprint(count, HexFormat.of().formatHex(digest.digest()));
        }
        return completed;
    }
}
