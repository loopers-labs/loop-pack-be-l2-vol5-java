package com.loopers.batch.job.ranking.domain;

public class InvalidPurchaseInputException extends RuntimeException {
    public InvalidPurchaseInputException(String message) {
        super(message);
    }
}
