package com.loopers.point.application.port.in;

/**
 * 포인트를 바꾸는 유스케이스의 입구(입력 포트). 조회는 PointQueryService가 맡는다.
 */
public interface PointCommandUseCase {

    PointBalanceInfo charge(Long userId, long amount);
}
