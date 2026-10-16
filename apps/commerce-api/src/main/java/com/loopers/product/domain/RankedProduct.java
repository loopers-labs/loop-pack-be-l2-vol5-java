package com.loopers.product.domain;

/** Like count computed for this ranking query, not a stored counter. */
public record RankedProduct(Product product, long likeCount) { }
