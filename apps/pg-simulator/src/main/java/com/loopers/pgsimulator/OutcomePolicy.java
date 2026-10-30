package com.loopers.pgsimulator;

/** 생성 결과를 정하는 확장점. 수동 실행은 금액 규칙을, 테스트는 미리 등록한 결과를 쓴다(설계 2.10·5.7). */
@FunctionalInterface
public interface OutcomePolicy {

    CreateOutcome decide(CreateAttempt attempt);
}
