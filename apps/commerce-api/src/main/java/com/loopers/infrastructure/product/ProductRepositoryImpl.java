package com.loopers.infrastructure.product;

import com.loopers.domain.product.ProductModel;
import com.loopers.domain.product.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
@Component
public class ProductRepositoryImpl implements ProductRepository {

    private final ProductJpaRepository productJpaRepository;

    /**
     * 즉시 flush해 @PrePersist·@PreUpdate(생성·수정 시각)가 응답을 만들기 전에 반영되게 한다.
     */
    @Override
    public ProductModel save(ProductModel product) {
        return productJpaRepository.saveAndFlush(product);
    }

    @Override
    public Optional<ProductModel> findActiveById(Long id) {
        return productJpaRepository.findByIdAndDeletedAtIsNull(id);
    }

    @Override
    public Page<ProductModel> findActive(Long brandId, Pageable pageable) {
        if (brandId == null) {
            return productJpaRepository.findAllByDeletedAtIsNull(pageable);
        }
        return productJpaRepository.findAllByBrandIdAndDeletedAtIsNull(brandId, pageable);
    }

    @Override
    public boolean existsActiveByBrandId(Long brandId) {
        return productJpaRepository.existsByBrandIdAndDeletedAtIsNull(brandId);
    }

    @Override
    public List<ProductModel> findAllByIds(Collection<Long> ids) {
        return productJpaRepository.findAllById(ids);
    }
}
