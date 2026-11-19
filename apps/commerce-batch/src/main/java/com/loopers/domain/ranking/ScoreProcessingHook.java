package com.loopers.domain.ranking;

/**
 * Step 2 processor 가 상품 합계를 계산하기 직전에 부르는 seam. 제품 기본 구현은 아무 일도 하지 않는다.
 * 테스트가 "상품 9 처리 직전 한 번 실패" 같은 중단 지점을 JobParameters 없이 만들기 위한 자리다 (설계 8절, W6·W7 의 hook 예외 방식과 같음).
 */
public interface ScoreProcessingHook {

    ScoreProcessingHook NO_OP = (snapshotId, productId) -> { };

    void beforeProcess(String snapshotId, long productId);
}
