package com.loopers.application.like;

import java.util.List;

public record LikeListInfo(List<Long> productIds) {
    public static LikeListInfo from(List<Long> productIds) {
        return new LikeListInfo(productIds);
    }
}
