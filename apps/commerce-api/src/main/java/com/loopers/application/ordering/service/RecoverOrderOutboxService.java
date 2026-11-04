package com.loopers.application.ordering.service;

import com.loopers.application.ordering.event.OrderConfirmedEvent;
import com.loopers.domain.ordering.repository.OrderRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RecoverOrderOutboxService {
    private final OrderRepository orders;
    private final AppendOrderOutboxService outbox;

    @Transactional
    public Result execute(long orderId, UUID eventId) {
        var order = orders.findByIdForUpdate(orderId)
            .orElseThrow(() -> new IllegalArgumentException("복구할 주문이 없습니다. orderId=" + orderId));
        var event = OrderConfirmedEvent.from(order);
        if (!event.eventId().equals(eventId)) {
            throw new IllegalArgumentException("지정한 UUID가 저장된 확정 사건과 다릅니다. orderId=" + orderId);
        }
        return outbox.insertOrVerify(event) ? Result.CREATED : Result.ALREADY_PRESENT;
    }

    public enum Result { CREATED, ALREADY_PRESENT }
}
