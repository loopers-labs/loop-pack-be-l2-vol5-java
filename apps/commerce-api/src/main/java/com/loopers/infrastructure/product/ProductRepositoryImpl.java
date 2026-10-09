package com.loopers.infrastructure.product;

import com.loopers.domain.product.ProductModel;
import com.loopers.domain.product.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
@Component
public class ProductRepositoryImpl implements ProductRepository {

    private final ProductJpaRepository productJpaRepository;

    @Override
    public ProductModel save(ProductModel product) {
        return productJpaRepository.save(product);
    }

    @Override
    public Optional<ProductModel> find(Long id) {
        return productJpaRepository.findById(id);
    }

    @Override
    public Optional<ProductModel> findForUpdate(Long id) {
        return productJpaRepository.findForUpdate(id);
    }

    @Override
    public List<ProductModel> findAllActive() {
        return productJpaRepository.findByDeletedAtIsNull();
    }

    @Override
    public List<ProductModel> findActiveByIds(Collection<Long> ids) {
        return productJpaRepository.findByIdInAndDeletedAtIsNull(ids);
    }

    @Override
    public List<ProductModel> findActiveByBrandIdForUpdate(Long brandId) {
        // BrandFacade holds the brand write lock before this transaction's first snapshot read.
        // Registration cannot add new products until deletion commits; brandId is immutable.
        return productJpaRepository.findActiveIdsByBrandId(brandId).stream()
            .map(productJpaRepository::findForUpdate)
            .flatMap(Optional::stream)
            .filter(product -> product.getDeletedAt() == null)
            .toList();
    }
}
