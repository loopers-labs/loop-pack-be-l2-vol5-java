package com.loopers.pgsimulator;

/**
 * 수동 실행 기본 정책(설계 2.10). 같은 금액은 항상 같은 경로로 간다. 422 생성 전 거절은 테스트에서만 고른다.
 * amount % 3: 0 → PENDING 뒤 SUCCESS, 1 → 즉시 SUCCESS, 2 → PENDING 뒤 FAILED.
 */
public final class AmountModuloOutcomePolicy implements OutcomePolicy {

    @Override
    public CreateOutcome decide(CreateAttempt attempt) {
        return switch ((int) (attempt.amount() % 3)) {
            case 1 -> CreateOutcome.IMMEDIATE_SUCCESS;
            case 2 -> CreateOutcome.PENDING_THEN_FAILED;
            default -> CreateOutcome.PENDING_THEN_SUCCESS;
        };
    }
}
