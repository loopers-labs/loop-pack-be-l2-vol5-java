package com.loopers.product.infrastructure;

import com.loopers.product.domain.Stock;
import com.loopers.product.domain.StockRepository;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;
import java.util.Optional;

@Component
public class StockRepositoryAdapter implements StockRepository {
    private final StockJpaRepository jpa;
    private final EntityManager entityManager;
    public StockRepositoryAdapter(StockJpaRepository jpa, EntityManager entityManager) { this.jpa = jpa; this.entityManager = entityManager; }
    public Stock save(Stock stock) { if (stock.getId() == 0L) entityManager.persist(stock); else jpa.save(stock); return stock; }
    public Optional<Stock> findByProductId(Long productId) { return jpa.findByProductId(productId); }
    public Optional<Stock> findForOrderByProductId(Long productId) { return jpa.findForOrderByProductId(productId); }
    public Optional<Stock> findForStockUpdateByProductId(Long productId) { return jpa.findForStockUpdateByProductId(productId); }
}
