package com.loopers.domain.order;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

/**
 * broker 가 확인한 record 위치(설계 W7.3-2). 실제 성공 metadata 의 topic·partition·offset 이며 소비 완료·PUBLISHED 저장이 아니다.
 */
public record OrderOutboxReceipt(String topic, int partition, long offset) {

    public OrderOutboxReceipt {
        if (topic == null || topic.isBlank() || partition < 0 || offset < 0L) {
            throw new CoreException(ErrorType.INTERNAL_ERROR, "broker 확인 위치가 올바르지 않습니다.");
        }
    }
}
