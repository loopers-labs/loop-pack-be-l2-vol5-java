package com.loopers.domain.purchase.enums;

public enum PurchaseRecordResult {
    /** 처음 받아 처리 기록과 모든 품목을 저장함 */
    RECORDED,
    /** 이미 같은 사실을 처리해 효과 없이 끝냄 (재전달) */
    ALREADY_RECORDED
}
