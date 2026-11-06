package com.loopers.infrastructure.product;

import com.loopers.domain.product.ProductListRevisionRepository;
import com.loopers.domain.product.ProductSortType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Component
public class ProductListRevisionRepositoryImpl implements ProductListRevisionRepository {
    private final ProductListRevisionJpaRepository productListRevisionJpaRepository;

    @Override
    public void increase(Long brandId, long slot, int latest, int price, int likes) {
        productListRevisionJpaRepository.increase(brandId, slot, latest, price, likes);
    }

    @Override
    public long findRevision(Long brandId, ProductSortType sortType) {
        return productListRevisionJpaRepository.sumByBrandId(brandId).of(sortType);
    }

    @Override
    public long sumRevisions(ProductSortType sortType) {
        return productListRevisionJpaRepository.sumAll().of(sortType);
    }
}
