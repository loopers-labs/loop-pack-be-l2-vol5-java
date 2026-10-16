package com.loopers.application.product;

/** Common product data plus request-scoped personal state; never a shared cache value. */
public record ProductListItemInfo(CustomerProductInfo product, boolean isLiked) {}
