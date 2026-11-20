package com.loopers.domain.ranking;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** An immutable result derived from all and only the independently verified frozen product totals. */
public final class CompletedRanking {
    private final WeeklyRankingKey key;
    private final List<RankedProduct> products;

    private CompletedRanking(WeeklyRankingKey key, List<RankedProduct> products) {
        this.key = key;
        this.products = List.copyOf(products);
    }

    public static CompletedRanking calculate(FrozenRankingInput input, List<ProductScore> scores) {
        if (input == null || scores == null) {
            throw new IllegalArgumentException("Completion requires frozen input and calculated scores");
        }
        var expected = new HashMap<Long, Long>();
        for (RankingPurchase purchase : input.purchases()) {
            expected.merge(purchase.productId(), (long) purchase.quantity(), Math::addExact);
        }
        var actual = new HashMap<Long, Long>();
        for (ProductScore score : scores) {
            if (score == null || !input.key().snapshotId().equals(score.snapshotId())
                || actual.putIfAbsent(score.productId(), score.score()) != null) {
                throw new RankingSnapshotConflictException("Completion requires one stage score per frozen product");
            }
        }
        if (!expected.equals(actual)) {
            throw new RankingSnapshotConflictException("Stage products or absolute totals differ from frozen input");
        }
        var ordered = actual.entrySet().stream()
            .sorted(Comparator.<Map.Entry<Long, Long>>comparingLong(Map.Entry::getValue).reversed()
                .thenComparingLong(Map.Entry::getKey)).toList();
        var results = new ArrayList<RankedProduct>();
        for (var total : ordered) {
            results.add(new RankedProduct(total.getKey(), total.getValue(), (long) results.size() + 1));
        }
        return new CompletedRanking(input.key(), results);
    }

    public WeeklyRankingKey key() {
        return key;
    }

    public List<RankedProduct> products() {
        return products;
    }

    public String checksumVersion() {
        return "v1";
    }

    public String checksum() {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (RankedProduct product : products) {
                String row = product.productId() + "\t" + product.score() + "\t" + product.rank() + "\n";
                digest.update(row.getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required for completed ranking checksums", exception);
        }
    }
}
