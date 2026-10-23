package com.loopers.product.adapter.out.persistence;

import com.loopers.product.application.port.out.StockReservationPort;
import com.loopers.product.domain.StockReservation;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@RequiredArgsConstructor
@Component
public class StockReservationPersistenceAdapter implements StockReservationPort {

    private final StockReservationJpaRepository stockReservationJpaRepository;

    @Override
    public List<StockReservation> saveAll(List<StockReservation> reservations) {
        return stockReservationJpaRepository.saveAll(reservations);
    }

    @Override
    public List<StockReservation> findByOrderIdForUpdate(Long orderId) {
        return stockReservationJpaRepository.findAllByOrderIdForUpdate(orderId);
    }

    @Override
    public List<StockReservation> findByOrderId(Long orderId) {
        return stockReservationJpaRepository.findAllByOrderIdOrderByProductIdAsc(orderId);
    }
}
