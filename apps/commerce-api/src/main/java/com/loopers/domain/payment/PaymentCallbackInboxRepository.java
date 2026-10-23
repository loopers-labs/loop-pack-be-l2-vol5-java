package com.loopers.domain.payment;

import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;

public interface PaymentCallbackInboxRepository {

    PaymentCallbackInbox save(PaymentCallbackInbox inbox);

    /** 처리할 차례인 행(받음, 또는 재시도 시각이 지난 행)을 받은 순서대로. */
    List<PaymentCallbackInbox> findDue(ZonedDateTime now, int limit);

    List<PaymentCallbackInbox> findAllByIdIn(Collection<Long> ids);
}
