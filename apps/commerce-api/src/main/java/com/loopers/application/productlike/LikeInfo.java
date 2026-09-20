package com.loopers.application.productlike;

import com.loopers.application.product.ProductInfo;

import java.time.ZonedDateTime;

/** 설계 4-3-0 LikeItem. */
public record LikeInfo(ZonedDateTime likedAt, ProductInfo product) {
}
