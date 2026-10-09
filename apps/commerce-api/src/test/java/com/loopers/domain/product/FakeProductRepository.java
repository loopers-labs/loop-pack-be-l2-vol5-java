package com.loopers.domain.product;

import com.loopers.domain.brand.Brand;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** DB 없이 상품 서비스의 협력을 확인하기 위한 저장 구현. */
public class FakeProductRepository implements ProductRepository {

    private final Map<Long, Product> products = new LinkedHashMap<>();
    private long sequence = 0L;

    @Override
    public Product save(Product product) {
        if (product.getId() == null || product.getId() == 0L) {
            ReflectionTestUtils.setField(product, "id", ++sequence);
        }
        products.put(product.getId(), product);
        return product;
    }

    @Override
    public Optional<Product> findActive(Long productId) {
        return Optional.ofNullable(products.get(productId)).filter(product -> !product.isDeleted());
    }

    // fake 에는 잠금이 없다. 잠금 동작은 실제 DB 의 동시성 테스트에서 확인한다
    @Override
    public Optional<Product> findActiveForUpdate(Long productId) {
        return findActive(productId);
    }

    @Override
    public List<Product> findAllActiveForUpdate(Collection<Long> productIds) {
        return productIds.stream()
            .distinct()
            .map(this::findActive)
            .flatMap(Optional::stream)
            .sorted(Comparator.comparing(Product::getId))
            .toList();
    }

    @Override
    public Set<Long> findActiveIds(Collection<Long> productIds) {
        return productIds.stream()
            .filter(productId -> findActive(productId).isPresent())
            .collect(Collectors.toSet());
    }

    @Override
    public Optional<ProductWithBrand> findActiveWithBrand(Long productId) {
        return findActive(productId).map(FakeProductRepository::withBrand);
    }

    @Override
    public Page<ProductWithBrand> findActiveWithBrand(Long brandId, Pageable pageable) {
        List<ProductWithBrand> active = products.values().stream()
            .filter(product -> !product.isDeleted())
            .map(FakeProductRepository::withBrand)
            .filter(product -> brandId == null || Objects.equals(product.brandId(), brandId))
            .toList();
        return new PageImpl<>(active, pageable, active.size());
    }

    // 좋아요 집계 · 정렬 조회는 쿼리의 동작이라 저장소 통합 테스트(실제 DB)에서 확인한다
    @Override
    public Optional<ProductView> findActiveView(Long productId) {
        throw new UnsupportedOperationException("ProductRepositoryIntegrationTest 에서 확인한다");
    }

    @Override
    public Page<ProductView> findActiveViews(Long brandId, ProductSort sort, Pageable pageable) {
        throw new UnsupportedOperationException("ProductRepositoryIntegrationTest 에서 확인한다");
    }

    @Override
    public int deleteAllOfBrand(Long brandId, ZonedDateTime deletedAt) {
        throw new UnsupportedOperationException("ProductRepositoryIntegrationTest 에서 확인한다");
    }

    public int count() {
        return products.size();
    }

    // Product 는 Brand 를 내보내지 않으므로(D-36) 저장 구현이 직접 꺼내 조회 결과 타입을 만든다
    private static ProductWithBrand withBrand(Product product) {
        Brand brand = (Brand) ReflectionTestUtils.getField(product, "brand");
        return new ProductWithBrand(
            product.getId(), product.getName(), product.getPrice(), product.getStock(),
            brand.getId(), brand.getName(), product.getCreatedAt(), product.getUpdatedAt()
        );
    }
}
