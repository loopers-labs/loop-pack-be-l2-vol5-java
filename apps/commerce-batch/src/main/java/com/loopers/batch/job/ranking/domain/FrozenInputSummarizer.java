package com.loopers.batch.job.ranking.domain;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

public final class FrozenInputSummarizer {
    private final RankingChecksum checksum = RankingChecksum.start();
    private final Set<Long> products = new HashSet<>();
    private int count;

    public void accept(long orderId, long productId, int quantity, LocalDateTime confirmedAtUtc) {
        if (quantity <= 0) {
            throw new InvalidPurchaseInputException(
                    "order " + orderId + " product " + productId + " has a non-positive quantity: " + quantity);
        }
        checksum.inputLine(orderId, productId, quantity, confirmedAtUtc);
        products.add(productId);
        count++;
    }

    public InputSummary summary() {
        return new InputSummary(count, products.size(), checksum.hex());
    }
}
