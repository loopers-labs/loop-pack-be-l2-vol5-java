package com.loopers.batch.job.ranking.domain;

public record ProductPurchaseTotal(long productId, long rowCount, long quantitySum, long minQuantity) {

    public ProductScore toScore() {
        if (rowCount == 0) {
            throw new InvalidPurchaseInputException("product " + productId + " has no frozen purchase rows");
        }
        if (minQuantity <= 0) {
            throw new InvalidPurchaseInputException(
                    "product " + productId + " has a non-positive quantity: " + minQuantity);
        }
        return new ProductScore(productId, quantitySum);
    }
}
