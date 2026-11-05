package com.loopers.domain.order;

/** PUBLISHED는 broker 성공을 확인해 저장했다는 뜻이며, 구매 활동 반영 완료를 뜻하지 않는다. */
public enum OrderOutboxStatus {
    PENDING,
    PUBLISHED
}
