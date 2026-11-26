package com.loopers.domains.payment.app.port;

import com.loopers.domains.payment.domain.RefundCase;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PaymentRefundCaseRepository {
    RefundCase save(RefundCase refundCase);

    Optional<RefundCase> findByOrderId(long orderId);

    Optional<RefundCase> findByOrderIdForUpdate(long orderId);

    List<Long> findDue(Instant now, int limit);
}
