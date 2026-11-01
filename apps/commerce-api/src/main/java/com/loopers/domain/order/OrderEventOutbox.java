package com.loopers.domain.order;

import java.time.Duration;
import java.util.List;

public interface OrderEventOutbox {

    void append(OrderEvent event);

    boolean appendIfAbsent(OrderEvent event);

    List<PendingOrderEvent> due(int limit);

    void markPublished(List<Long> ids);

    void markBlocked(Long id, String error);

    void retryLater(Long id, Duration delay, String error);
}
