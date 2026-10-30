package com.loopers.application.purchaseactivity;

/** 처리 TX 가 정상 종료된 결과. 둘 다 DB 효과가 확정된 뒤에만 반환된다. */
public enum PurchaseActivityResult {
    /** 이번 TX 가 완료 기록과 모든 활동을 commit 했다. */
    APPLIED,
    /** 같은 사실이 이미 저장돼 있어 DB 를 바꾸지 않았다. */
    ALREADY_PROCESSED
}
