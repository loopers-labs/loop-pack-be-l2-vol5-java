package com.loopers.batch.job.ranking.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.StringJoiner;

public final class RankingChecksum {
    private static final DateTimeFormatter UTC_MICROS = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'");

    private final MessageDigest digest;

    private RankingChecksum() {
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    public static RankingChecksum start() {
        return new RankingChecksum();
    }

    public RankingChecksum inputLine(long orderId, long productId, int quantity, LocalDateTime confirmedAtUtc) {
        return line(Long.toString(orderId), Long.toString(productId), Integer.toString(quantity),
                UTC_MICROS.format(confirmedAtUtc));
    }

    public RankingChecksum resultLine(long productId, long score, int rank) {
        return line(Long.toString(productId), Long.toString(score), Integer.toString(rank));
    }

    public String hex() {
        try {
            MessageDigest snapshot = (MessageDigest) digest.clone();
            return HexFormat.of().formatHex(snapshot.digest());
        } catch (CloneNotSupportedException e) {
            throw new IllegalStateException("SHA-256 provider must support digest cloning", e);
        }
    }

    private RankingChecksum line(String... fields) {
        var joiner = new StringJoiner("\t", "", "\n");
        for (String field : fields) {
            joiner.add(field);
        }
        digest.update(joiner.toString().getBytes(StandardCharsets.UTF_8));
        return this;
    }
}
