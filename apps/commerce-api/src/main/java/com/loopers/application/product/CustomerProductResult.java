package com.loopers.application.product;

public record CustomerProductResult(long id, String name, long price, int stock,
    long brandId, String brandName, long likeCount) { }
