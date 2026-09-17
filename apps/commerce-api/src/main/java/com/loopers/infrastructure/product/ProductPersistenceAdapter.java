package com.loopers.infrastructure.product;

import com.loopers.application.product.port.ProductRepository;
import com.loopers.domain.brand.BrandId;
import com.loopers.domain.common.Money;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductId;
import com.loopers.domain.product.Stock;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.util.Optional;

@Repository
@Transactional(readOnly = true)
public class ProductPersistenceAdapter implements ProductRepository {
    private final ProductJpaRepository repository;

    public ProductPersistenceAdapter(ProductJpaRepository repository) { this.repository = repository; }

    @Override
    @Transactional
    public Product save(Product product) {
        ProductJpaEntity entity;
        if (product.getId() == null) {
            entity = new ProductJpaEntity(product.getBrandId().value(), product.getName(),
                product.getPrice().value(), product.getStock().value());
        } else {
            entity = repository.findById(product.getId().value()).orElseThrow();
        }
        entity.update(product.getName(), product.getPrice().value(), product.getStock().value(), product.isDeleted());
        return toDomain(repository.save(entity));
    }

    @Override
    public Optional<Product> findById(ProductId id) { return repository.findById(id.value()).map(this::toDomain); }

    @Override
    @Transactional
    public Optional<Product> findByIdForUpdate(ProductId id) { return repository.findForUpdate(id.value()).map(this::toDomain); }

    @Override
    public boolean existsActiveByBrandId(BrandId id) { return repository.existsByBrandIdAndDeletedFalse(id.value()); }

    private Product toDomain(ProductJpaEntity entity) {
        return Product.restore(new ProductId(entity.getId()), new BrandId(entity.getBrandId()), entity.getName(),
            new Money(entity.getPrice()), new Stock(entity.getStock()), entity.isDeleted());
    }
}
