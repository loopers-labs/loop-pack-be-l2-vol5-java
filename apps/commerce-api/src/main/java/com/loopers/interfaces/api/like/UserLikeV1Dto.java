package com.loopers.interfaces.api.like;

import com.loopers.application.like.LikeListInfo;

import java.util.List;

public class UserLikeV1Dto {
    public record LikeListResponse(List<Long> productIds) {
        public static LikeListResponse from(LikeListInfo info) {
            return new LikeListResponse(info.productIds());
        }
    }
}
