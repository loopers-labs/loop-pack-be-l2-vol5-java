package com.loopers.domain.outbox;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.util.List;

@RequiredArgsConstructor
@Component
public class OutboxService {

    private final OutboxRepository outboxRepository;

    /** 호출한 쪽의 트랜잭션에 참여해 저장한다. eventId 유일 제약이 같은 사건의 중복 기록을 막는다. */
    public Outbox save(Outbox outbox) {
        return outboxRepository.save(outbox);
    }

    public Outbox get(Long id) {
        return outboxRepository.findById(id).orElseThrow(
            () -> new CoreException(ErrorType.NOT_FOUND, "Outbox 를 찾을 수 없습니다. (ID: " + id + ")")
        );
    }

    /** 전송할 차례가 된 PENDING 을 기록한 순서대로 최대 {@code limit} 개 가져온다. */
    public List<Outbox> findDueForDelivery(ZonedDateTime now, int limit) {
        return outboxRepository.findDueForDelivery(now, limit);
    }
}
