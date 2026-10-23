package com.loopers.infrastructure.order;

import com.loopers.domain.order.StockReservationModel;
import com.loopers.domain.order.StockReservationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

@RequiredArgsConstructor
@Component
public class StockReservationRepositoryImpl implements StockReservationRepository {
    private final StockReservationJpaRepository stockReservationJpaRepository;

    @Override
    public StockReservationModel save(StockReservationModel reservation) {
        return stockReservationJpaRepository.save(reservation);
    }

    @Override
    public Optional<StockReservationModel> findByOrderId(Long orderId) {
        return stockReservationJpaRepository.findByOrderId(orderId);
    }

    @Override
    public Optional<StockReservationModel> findByOrderIdForUpdate(Long orderId) {
        return stockReservationJpaRepository.findByOrderIdForUpdate(orderId);
    }
}
