package com.loopers.infrastructure.product;

import com.loopers.domain.product.CatalogRevisionModel;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.product.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
@Component
public class ProductRepositoryImpl implements ProductRepository {
    private final ProductJpaRepository productJpaRepository;
    private final CatalogRevisionJpaRepository catalogRevisionJpaRepository;

    @Override
    public ProductModel save(ProductModel product) {
        return productJpaRepository.save(product);
    }

    @Override
    public Optional<ProductModel> find(Long id) {
        if (id == null) {
            return Optional.empty();
        }
        return productJpaRepository.findById(id);
    }

    @Override
    public List<ProductModel> findByIds(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return productJpaRepository.findAllById(ids);
    }

    @Override
    public List<ProductModel> findByIdsForUpdate(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return productJpaRepository.findAllByIdInForUpdate(ids);
    }

    @Override
    public List<ProductModel> findActiveByBrandId(Long brandId) {
        return productJpaRepository.findAllByBrandIdAndDeletedAtIsNull(brandId);
    }

    /** 행이 없으면 목록 캐시가 쓰기를 모른 채 남으므로, 쓰기가 커밋되지 않게 실패시킨다 (DR-37). */
    @Override
    public void increaseListRevision() {
        int updated = catalogRevisionJpaRepository.increaseRevision(CatalogRevisionModel.PRODUCT_LIST_LATEST, ZonedDateTime.now());
        if (updated != 1) {
            throw new IllegalStateException("[name = " + CatalogRevisionModel.PRODUCT_LIST_LATEST + "] 목록 revision 행이 없습니다.");
        }
    }
}
