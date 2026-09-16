package com.loopers.infrastructure.product;

import com.loopers.domain.product.ProductModel;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.product.ProductSortType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
@Component
public class ProductRepositoryImpl implements ProductRepository {

    private final ProductJpaRepository productJpaRepository;

    @Override
    public Optional<ProductModel> findActiveById(Long id) {
        return productJpaRepository.findByIdAndDeletedAtIsNull(id);
    }

    @Override
    public Optional<ProductModel> findById(Long id) {
        return productJpaRepository.findById(id);
    }

    @Override
    public Page<ProductModel> findActiveProducts(Long brandId, ProductSortType sortType, Pageable pageable) {
        return switch (sortType) {
            case LATEST -> productJpaRepository.findActiveOrderByLatest(brandId, pageable);
            case PRICE_ASC -> productJpaRepository.findActiveOrderByPriceAsc(brandId, pageable);
            case LIKES_DESC -> productJpaRepository.findActiveOrderByLikesDesc(brandId, pageable);
        };
    }

    @Override
    public List<ProductModel> findAllActiveByIds(List<Long> ids) {
        return productJpaRepository.findByIdInAndDeletedAtIsNull(ids);
    }

    @Override
    public Page<ProductModel> findAll(Pageable pageable) {
        return productJpaRepository.findAll(pageable);
    }

    @Override
    public boolean existsActiveByBrandId(Long brandId) {
        return productJpaRepository.existsByBrandIdAndDeletedAtIsNull(brandId);
    }

    @Override
    public ProductModel save(ProductModel product) {
        return productJpaRepository.save(product);
    }
}
