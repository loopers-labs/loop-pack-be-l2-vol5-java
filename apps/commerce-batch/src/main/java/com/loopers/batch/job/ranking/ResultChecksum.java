package com.loopers.batch.job.ranking;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

// 결과 checksum(docs/week9/commerce-ranking.md 7절). 전체 계산 순위를 calc_rank 순으로 놓고 "productId\tscore\tcalcRank\n"을
// 이어 붙인 UTF-8 바이트의 SHA-256 소문자 hex다. 새 칸 없이 계산 함수로만 둔다.
public final class ResultChecksum {
    private ResultChecksum() {
    }

    public static String of(List<RankedScore> ranking) {
        StringBuilder canonical = new StringBuilder();
        ranking.forEach(row -> canonical.append(row.productId()).append('\t').append(row.score()).append('\t')
            .append(row.calcRank()).append('\n'));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 쓸 수 없습니다.", e);
        }
    }
}
