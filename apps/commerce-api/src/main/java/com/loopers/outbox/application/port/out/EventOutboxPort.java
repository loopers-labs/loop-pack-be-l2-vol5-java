package com.loopers.outbox.application.port.out;

import com.loopers.outbox.domain.EventOutbox;
import com.loopers.outbox.domain.OutboxMessage;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

public interface EventOutboxPort {

    /**
     * payload를 JSON으로 한 번 바꿔 기록하고, 저장한 그 문자열을 돌려준다.
     */
    String append(OutboxMessage message);

    /**
     * 아직 성공 표시가 없을 때만 성공을 표시한다.
     */
    void markPublished(String eventId, int partition, long offset, ZonedDateTime publishedAt);

    /**
     * 아직 성공 표시가 없을 때만 마지막 실패 원인을 남긴다.
     */
    void markFailed(String eventId, String error);

    Optional<EventOutbox> findByEventId(String eventId);

    /**
     * 누락 조회 (ADR-W6-03): 기준 시각 전에 만들어졌는데 아직 성공 표시가 없는 기록.
     */
    List<EventOutbox> findUnpublishedCreatedBefore(ZonedDateTime createdBefore);
}
