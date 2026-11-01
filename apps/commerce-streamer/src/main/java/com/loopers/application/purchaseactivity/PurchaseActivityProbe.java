package com.loopers.application.purchaseactivity;

/**
 * 처리 TX 의 중간 지점. 운영 구현은 아무것도 하지 않고, 테스트가 실제 SQL 실행 뒤 실패·장벽을 주입하는 데 쓴다.
 * 공개 제어 기능이 아니다.
 */
public interface PurchaseActivityProbe {

    /** 활동 한 건의 INSERT 가 실행된 뒤, 같은 TX 안에서 호출된다. */
    void afterActivitySaved(long orderId, long productId);

    /** 모든 쓰기를 마치고 commit 하기 직전, 같은 TX 안에서 호출된다. */
    void beforeCommit(long orderId);
}
