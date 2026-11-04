package com.loopers.infrastructure.order;

import com.loopers.domain.order.OrderConfirmedOutboxModel;
import com.loopers.domain.order.OrderConfirmedOutboxRepository;
import com.loopers.domain.order.OrderOutboxStatus;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
@Component
public class OrderConfirmedOutboxRepositoryImpl implements OrderConfirmedOutboxRepository {

    private final OrderConfirmedOutboxJpaRepository jpaRepository;

    /**
     * MANDATORY 로 호출자 TX 를 요구해 Outbox 만 따로 commit 되는 경로를 막는다. flush 는 Spring Data repository 프록시의
     * saveAndFlush 로만 수행해 예외 변환을 거친다(설계 W7.2-6a). 같은 PC 의 다른 변경도 함께 SQL 로 반영될 수 있다.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public OrderConfirmedOutboxModel insertAndFlush(OrderConfirmedOutboxModel outbox) {
        if (outbox == null) {
            throw new CoreException(ErrorType.INTERNAL_ERROR, "저장할 Outbox 행이 없습니다.");
        }
        return jpaRepository.saveAndFlush(outbox);
    }

    @Override
    public Optional<OrderConfirmedOutboxModel> findByEventId(String eventId) {
        return jpaRepository.findByEventId(eventId);
    }

    @Override
    public List<Long> findPendingDueIds(ZonedDateTime now, int limit) {
        if (now == null) {
            throw new CoreException(ErrorType.INTERNAL_ERROR, "due 조회 기준 시각이 없습니다.");
        }
        if (limit < 1) {
            throw new CoreException(ErrorType.INTERNAL_ERROR, "due 조회 limit 은 양수여야 합니다.");
        }
        return jpaRepository.findDueIds(OrderOutboxStatus.PENDING, now, Limit.of(limit));
    }
}
