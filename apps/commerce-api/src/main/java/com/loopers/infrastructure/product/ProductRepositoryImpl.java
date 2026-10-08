package com.loopers.infrastructure.product;

import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
@Transactional
public class ProductRepositoryImpl implements ProductRepository {
    private final ProductJpaRepository repository;

    @Override
    @Transactional(readOnly = true)
    public Optional<Product> findById(long id) {
        return repository.findById(id).map(ProductJpaEntity::toDomain);
    }

    @Override
    public Product save(Product product) {
        ProductJpaEntity entity =
                product.getId() == null
                        ? new ProductJpaEntity(product)
                        : repository.findById(product.getId()).orElseThrow();
        entity.update(product);
        return repository.save(entity).toDomain();
    }

    @Override
    @Transactional(readOnly = true)
    public Page<Product> findAll(Pageable pageable) {
        return repository.findAll(pageable).map(ProductJpaEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Long> findActiveIdsByBrandId(long brandId) {
        return repository.findIdsByBrandIdAndDeletedAtIsNullOrderByIdAsc(brandId).stream()
                .map(ProductJpaRepository.ProductId::getId)
                .toList();
    }

    @Override
    public void delete(long id) {
        repository.markDeleted(id, ZonedDateTime.now());
    }

    @Override
    public void deductStock(long id, int quantity) {
        Product.validateQuantity(quantity);
        if (repository.deductStock(id, quantity, ZonedDateTime.now()) == 0) {
            Product current =
                    repository
                            .findCurrentById(id)
                            .map(ProductJpaEntity::toDomain)
                            .orElseThrow(() -> new CoreException(ErrorType.PRODUCT_NOT_FOUND));
            current.requireActive();
            throw new CoreException(ErrorType.INSUFFICIENT_STOCK);
        }
    }

    @Override
    public Product setStock(long id, int stock) {
        Product.validateStock(stock);
        if (repository.setStock(id, stock, ZonedDateTime.now()) == 0) {
            throw new CoreException(ErrorType.PRODUCT_NOT_FOUND);
        }
        return repository.findById(id).orElseThrow().toDomain();
    }

    @Override
    public Product updateInformation(Product product) {
        product.requireActive();
        if (repository.updateInformation(
                        product.getId(), product.getName(), product.getPrice(), ZonedDateTime.now())
                == 0) {
            throw new CoreException(ErrorType.PRODUCT_NOT_FOUND);
        }
        return repository.findById(product.getId()).orElseThrow().toDomain();
    }
}
