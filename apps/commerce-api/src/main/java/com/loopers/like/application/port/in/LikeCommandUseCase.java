package com.loopers.like.application.port.in;

/**
 * 좋아요를 바꾸는 유스케이스의 입구(입력 포트). 조회는 LikeQueryService가 맡는다.
 */
public interface LikeCommandUseCase {

    void like(Long userId, Long productId);

    void unlike(Long userId, Long productId);
}
