package com.loopers.pgsimulator;

/** 헤더·입력 검증을 통과한 생성 요청. 결과 정책에는 카드 값을 넘기지 않는다(설계 2.10). */
public record CreateAttempt(String orderId, long amount) {
}
