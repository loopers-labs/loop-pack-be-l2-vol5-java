package com.loopers.domain.ordering.repository;

import java.util.UUID;

public interface OrderEventFailureRepository {
    void insertOrVerify(Failure failure);

    record Failure(String group, String topic, int partition, long offset, String key, byte[] value,
                   UUID eventId, String reason) {}
}
