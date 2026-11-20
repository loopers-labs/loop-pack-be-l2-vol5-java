package com.loopers.batch.job.ranking;

import java.time.Instant;

// 원본 product_purchase_activity의 한 행이자 snapshot 입력의 한 행.
public record SourcePurchase(long orderId, long productId, int quantity, Instant confirmedAt) {
}
