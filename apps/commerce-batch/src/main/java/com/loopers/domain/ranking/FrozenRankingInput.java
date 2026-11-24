package com.loopers.domain.ranking;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

public record FrozenRankingInput(WeeklyRankingKey key, Instant frozenAt, List<RankingPurchase> purchases) {
    private static final DateTimeFormatter CONFIRMATION_TIME = new DateTimeFormatterBuilder().appendInstant(9).toFormatter();
    private static final Comparator<RankingPurchase> CANONICAL_ORDER = Comparator.comparingLong(RankingPurchase::orderId)
        .thenComparingLong(RankingPurchase::productId);

    public FrozenRankingInput {
        if (key == null || frozenAt == null || purchases == null) {
            throw new IllegalArgumentException("Frozen ranking input requires its identity, observation instant and purchases");
        }
        for (RankingPurchase purchase : purchases) {
            if (purchase == null || !key.contains(purchase.confirmedAt())) {
                throw new IllegalArgumentException("Every frozen purchase must belong to the requested UTC week");
            }
        }
        purchases = purchases.stream().sorted(CANONICAL_ORDER).toList();
        for (int index = 1; index < purchases.size(); index++) {
            if (CANONICAL_ORDER.compare(purchases.get(index - 1), purchases.get(index)) == 0) {
                throw new IllegalArgumentException("Frozen input must contain each order and product only once");
            }
        }
    }

    public long inputCount() {
        return purchases.size();
    }

    public long productCount() {
        return purchases.stream().mapToLong(RankingPurchase::productId).distinct().count();
    }

    public String checksumVersion() {
        return "v1";
    }

    public String inputChecksum() {
        MessageDigest digest = sha256();
        for (RankingPurchase purchase : purchases) {
            String row = purchase.orderId() + "\t" + purchase.productId() + "\t" + purchase.quantity() + "\t"
                + CONFIRMATION_TIME.format(purchase.confirmedAt()) + "\n";
            digest.update(row.getBytes(StandardCharsets.UTF_8));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required for ranking input checksums", exception);
        }
    }
}
