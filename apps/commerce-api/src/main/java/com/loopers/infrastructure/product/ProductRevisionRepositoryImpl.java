package com.loopers.infrastructure.product;

import com.loopers.domain.product.ProductRevisionModel;
import com.loopers.domain.product.ProductRevisionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Component
public class ProductRevisionRepositoryImpl implements ProductRevisionRepository {
    private final ProductRevisionJpaRepository productRevisionJpaRepository;

    @Override
    public void increase(Long productId) {
        productRevisionJpaRepository.increase(productId);
    }

    @Override
    public Map<Long, Long> findRevisions(List<Long> productIds) {
        return productRevisionJpaRepository.findAllById(productIds).stream()
            .collect(Collectors.toMap(ProductRevisionModel::getProductId, ProductRevisionModel::getRevision));
    }
}
