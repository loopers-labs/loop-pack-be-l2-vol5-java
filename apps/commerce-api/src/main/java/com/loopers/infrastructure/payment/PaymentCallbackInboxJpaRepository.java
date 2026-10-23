package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.CallbackInboxStatus;
import com.loopers.domain.payment.PaymentCallbackInbox;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;

public interface PaymentCallbackInboxJpaRepository extends JpaRepository<PaymentCallbackInbox, Long> {

    @Query("SELECT i FROM PaymentCallbackInbox i WHERE i.status IN :statuses AND i.nextTryAt <= :now ORDER BY i.receivedAt ASC, i.id ASC")
    List<PaymentCallbackInbox> findDue(@Param("statuses") Collection<CallbackInboxStatus> statuses,
                                       @Param("now") ZonedDateTime now, Pageable pageable);

    List<PaymentCallbackInbox> findAllByIdIn(Collection<Long> ids);
}
