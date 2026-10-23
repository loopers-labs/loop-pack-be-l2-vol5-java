package com.loopers.infrastructure.product;

import com.loopers.domain.product.StockReservation;
import com.loopers.domain.product.StockReservationRepository;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.util.List;

import static com.loopers.domain.product.QStockReservation.stockReservation;

@RequiredArgsConstructor
@Component
public class StockReservationRepositoryImpl implements StockReservationRepository {
    private final StockReservationJpaRepository stockReservationJpaRepository;
    private final JPAQueryFactory queryFactory;

    @Override
    public StockReservation save(StockReservation reservation) {
        return stockReservationJpaRepository.save(reservation);
    }

    @Override
    public List<StockReservation> findAllByOrderId(Long orderId) {
        return stockReservationJpaRepository.findAllByOrderId(orderId);
    }

    @Override
    public int consumeIfReserved(Long reservationId) {
        return transitionFromReserved(reservationId, StockReservation.Status.CONSUMED);
    }

    @Override
    public int releaseIfReserved(Long reservationId) {
        return transitionFromReserved(reservationId, StockReservation.Status.RELEASED);
    }

    private int transitionFromReserved(Long reservationId, StockReservation.Status next) {
        return (int) queryFactory
            .update(stockReservation)
            .set(stockReservation.status, next)
            .set(stockReservation.updatedAt, ZonedDateTime.now())
            .where(
                stockReservation.id.eq(reservationId),
                stockReservation.status.eq(StockReservation.Status.RESERVED)
            )
            .execute();
    }
}