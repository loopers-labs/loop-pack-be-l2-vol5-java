package com.loopers.infrastructure.product;

import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
@Transactional
public class ProductRepositoryImpl implements ProductRepository {
    private final ProductJpaRepository repository;
    @Override
    @Transactional(readOnly = true)
    public Optional<Product> findById(long id) { return repository.findById(id).map(ProductJpaEntity::toDomain); }
    @Override
    public Product save(Product product) {
        ProductJpaEntity entity = product.getId() == null ? new ProductJpaEntity(product) : repository.findById(product.getId()).orElseThrow();
        entity.update(product);
        return repository.save(entity).toDomain();
    }
    @Override
    @Transactional(readOnly = true)
    public Page<Product> findAll(Pageable pageable) { return repository.findAll(pageable).map(ProductJpaEntity::toDomain); }
}
