package com.loopers.application.pay.query;

import java.time.Instant;
import java.util.OptionalLong;

public interface PaymentMetricQueryDao {
    /** 발송한 UNKNOWN의 기한을 판정할 수 없는 행이 있으면 빈 결과를 반환한다. */
    OptionalLong countOverdueUnknown(Instant observedAt);
}
