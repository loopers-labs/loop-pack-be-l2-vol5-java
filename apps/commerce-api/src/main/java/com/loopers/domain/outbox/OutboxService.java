package com.loopers.domain.outbox;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Component
public class OutboxService {

    private final OutboxRepository outboxRepository;

    /** 호출한 쪽의 트랜잭션에 참여해 저장한다. eventId 유일 제약이 같은 사건의 중복 기록을 막는다. */
    public Outbox save(Outbox outbox) {
        return outboxRepository.save(outbox);
    }
}
