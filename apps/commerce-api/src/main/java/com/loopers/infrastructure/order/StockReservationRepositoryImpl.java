package com.loopers.infrastructure.order;

import com.loopers.domain.order.StockReservation;
import com.loopers.domain.order.StockReservationRepository;
import com.loopers.domain.order.StockReservationStatus;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.time.ZonedDateTime;

@Repository
@RequiredArgsConstructor
@Transactional
public class StockReservationRepositoryImpl implements StockReservationRepository {
    private final StockReservationJpaRepository repository;

    @Override
    public void holdAll(List<StockReservation> reservations) {
        repository.saveAll(reservations.stream().map(StockReservationJpaEntity::new).toList());
    }

    @Override
    public void consumeHeldForOrder(long orderId, int expectedReservations) {
        changeHeldStatus(orderId, expectedReservations, StockReservationStatus.CONSUMED);
    }

    @Override
    public List<StockReservation> releaseHeldForOrder(long orderId, int expectedReservations) {
        var heldReservations = repository.findByOrderIdAndStatusOrderByProductIdAsc(orderId, StockReservationStatus.HELD)
                .stream().map(StockReservationJpaEntity::toDomain).toList();
        if (heldReservations.size() != expectedReservations) {
            throw new CoreException(ErrorType.CONFLICT);
        }
        changeHeldStatus(orderId, expectedReservations, StockReservationStatus.RELEASED);
        return heldReservations;
    }

    private void changeHeldStatus(long orderId, int expectedReservations, StockReservationStatus nextStatus) {
        if (repository.changeHeldStatus(orderId, StockReservationStatus.HELD, nextStatus, ZonedDateTime.now())
                != expectedReservations) {
            throw new CoreException(ErrorType.CONFLICT);
        }
    }
}
