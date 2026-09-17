package com.loopers.infrastructure.product;

import com.loopers.domain.product.StockHistoryModel;
import com.loopers.domain.product.StockHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Component
public class StockHistoryRepositoryImpl implements StockHistoryRepository {

    private final StockHistoryJpaRepository stockHistoryJpaRepository;

    @Override
    public StockHistoryModel save(StockHistoryModel history) {
        return stockHistoryJpaRepository.save(history);
    }
}
