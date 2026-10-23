package com.loopers.infrastructure.product;

import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.product.ProductSort;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.math.BigInteger;
import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
@Component
public class ProductRepositoryImpl implements ProductRepository {

    private final ProductJpaRepository productJpaRepository;
    private final BrandJpaRepository brandJpaRepository;

    @Override
    public Optional<Product> findById(Long productId) {
        return productJpaRepository.findById(productId).map(ProductJpaMapper::toDomain);
    }

    @Override
    public Optional<Product> findByIdWithExclusiveLock(Long productId) {
        return productJpaRepository.findByIdWithExclusiveLock(productId).map(ProductJpaMapper::toDomain);
    }

    @Override
    public List<Product> findAll() {
        return productJpaRepository.findAll().stream().map(ProductJpaMapper::toDomain).toList();
    }

    @Override
    public List<Product> findAllActive() {
        return productJpaRepository.findAllByDeletedAtIsNull().stream().map(ProductJpaMapper::toDomain).toList();
    }

    @Override
    public List<Product> findActivePage(Long brandId, ProductSort sort, int page, int size) {
        // JPA offset의 int 범위를 벗어난 요청은 기존 계약대로 빈 페이지를 반환한다.
        if ((long) page * size > Integer.MAX_VALUE) {
            return List.of();
        }
        Sort jpaSort = switch (sort) {
            case LATEST -> Sort.by(
                Sort.Order.desc("createdAt"),
                Sort.Order.desc("id")
            );
            case PRICE_ASC -> Sort.by(
                Sort.Order.asc("price"),
                Sort.Order.asc("id")
            );
            case LIKES_DESC -> Sort.unsorted();
        };
        Pageable pageable = PageRequest.of(page, size, jpaSort);
        List<ProductJpaEntity> entities = switch (sort) {
            case LATEST, PRICE_ASC -> findActivePage(brandId, pageable);
            case LIKES_DESC -> brandId == null
                ? productJpaRepository.findAllActiveOrderByLikeCountDesc(pageable)
                : productJpaRepository.findAllActiveByBrandIdOrderByLikeCountDesc(brandId, pageable);
        };
        return entities.stream().map(ProductJpaMapper::toDomain).toList();
    }

    private List<ProductJpaEntity> findActivePage(Long brandId, Pageable pageable) {
        if (brandId == null) {
            return productJpaRepository.findAllByDeletedAtIsNull(pageable);
        }
        return productJpaRepository.findAllByDeletedAtIsNullAndBrand_Id(brandId, pageable);
    }

    @Override
    public long countActiveByIds(List<Long> productIds) {
        return productJpaRepository.countByIdInAndDeletedAtIsNull(productIds);
    }

    @Override
    public List<Product> findAllDeleted() {
        return productJpaRepository.findAllByDeletedAtIsNotNull().stream().map(ProductJpaMapper::toDomain).toList();
    }

    @Override
    public int updateActiveDetails(Long productId, String name, long price, ZonedDateTime updatedAt) {
        return productJpaRepository.updateActiveDetails(productId, name, price, updatedAt);
    }

    @Override
    public int updateActiveStock(Long productId, long stock, ZonedDateTime updatedAt) {
        return productJpaRepository.updateActiveStock(productId, stock, updatedAt);
    }

    @Override
    public int decreaseActiveStock(Long productId, int quantity, ZonedDateTime updatedAt) {
        return productJpaRepository.decreaseActiveStock(productId, quantity, updatedAt);
    }

    @Override
    public int increaseStock(Long productId, int quantity, ZonedDateTime updatedAt) {
        return productJpaRepository.increaseStock(productId, quantity, updatedAt);
    }

    @Override
    public BigInteger sumPendingReservationQuantity(Long productId) {
        return productJpaRepository.sumPendingReservationQuantity(productId).toBigIntegerExact();
    }

    @Override
    public int softDeleteActiveById(Long productId, ZonedDateTime deletedAt) {
        return productJpaRepository.softDeleteActiveById(productId, deletedAt);
    }

    @Override
    public int softDeleteActiveByBrandId(Long brandId, ZonedDateTime deletedAt) {
        return productJpaRepository.softDeleteActiveByBrandId(brandId, deletedAt);
    }

    @Override
    public Product save(Product product) {
        ProductJpaEntity entity;
        if (product.getId() == null) {
            var brand = brandJpaRepository.getReferenceById(product.getBrandId());
            entity = ProductJpaMapper.toNewEntity(product, brand);
        } else {
            entity = productJpaRepository.findById(product.getId()).orElseThrow(
                () -> new IllegalArgumentException("Product does not exist: " + product.getId())
            );
            ProductJpaMapper.update(product, entity);
        }
        return ProductJpaMapper.toDomain(productJpaRepository.save(entity));
    }
}
