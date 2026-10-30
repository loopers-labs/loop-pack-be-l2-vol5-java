package com.loopers.application.purchase.dto;

/** 구매 활동 저장의 결과. 두 값 모두 업무가 끝났다는 뜻이라 호출자는 ack 할 수 있다. */
public enum PurchaseSaveResult {
    /** 처음 저장했다. */
    SAVED,
    /** 같은 사실이 이미 저장돼 있어 아무것도 더하지 않았다. */
    DUPLICATE
}
