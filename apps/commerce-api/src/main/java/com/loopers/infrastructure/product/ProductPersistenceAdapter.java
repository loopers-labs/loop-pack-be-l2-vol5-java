package com.loopers.infrastructure.product;

import com.loopers.application.product.port.ProductRepository;
import com.loopers.domain.brand.BrandId;
import com.loopers.domain.common.Money;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductId;
import com.loopers.domain.product.Stock;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(readOnly = true)
public class ProductPersistenceAdapter implements ProductRepository {
    private final ProductJpaRepository productJpaRepository;
    private final EntityManager entityManager;

    public ProductPersistenceAdapter(ProductJpaRepository productJpaRepository, EntityManager entityManager) {
        this.productJpaRepository = productJpaRepository;
        this.entityManager = entityManager;
    }

    @Override
    @Transactional
    public Product save(Product product) {
        ProductJpaEntity entity;
        if (product.getId() == null) {
            entity = new ProductJpaEntity(product.getBrandId().value(), product.getName(),
                product.getPrice().value(), product.getStock().value());
        } else {
            entity = productJpaRepository.findById(product.getId().value()).orElseThrow();
        }
        entity.update(product.getName(), product.getPrice().value(), product.getStock().value(), product.isDeleted());
        return toDomain(productJpaRepository.save(entity));
    }

    @Override
    public Optional<Product> findById(ProductId id) {
        return productJpaRepository.findById(id.value()).map(this::toDomain);
    }

    @Override
    @Transactional
    public Optional<Product> findByIdForUpdate(ProductId id) {
        return productJpaRepository.findForUpdate(id.value()).map(this::toDomain);
    }

    @Override
    @Transactional
    public List<Product> findActiveByBrandIdForUpdate(BrandId brandId) {
        // products.brand_id에는 인덱스가 없어 brand_id 조건으로 바로 FOR UPDATE를 걸면 스캔한 다른 브랜드 상품 행까지 잠긴다.
        // 대상 ID만 잠금 없이 조회한 뒤 기본 키로 잠근다. 호출 전 브랜드 행을 잠가 두므로 그 사이 같은 브랜드 상품은 생기지 않는다.
        List<Long> activeIds = productJpaRepository.findActiveIdsByBrandId(brandId.value());
        if (activeIds.isEmpty()) {
            return List.of();
        }
        return productJpaRepository.findAllByIdInForUpdate(activeIds).stream()
            .filter(entity -> !entity.isDeleted())
            .map(this::toDomain)
            .toList();
    }

    @Override
    public List<Product> findPage(int page, int size) {
        return productJpaRepository.findAll(PageRequest.of(page, size, Sort.by("id").descending()))
            .stream()
            .map(this::toDomain)
            .toList();
    }

    @Override
    public List<Product> findAllByIds(Collection<ProductId> ids) {
        return productJpaRepository.findAllById(ids.stream().map(ProductId::value).toList())
            .stream()
            .map(this::toDomain)
            .toList();
    }

    @Override
    public List<Product> search(Long brandId, int page, int size, String sort) {
        String order = switch (sort) {
            case "latest" -> "p.created_at desc, p.id desc";
            case "price_asc" -> "p.price asc, p.id desc";
            case "likes_desc" -> "(select count(*) from product_likes l where l.product_id=p.id) desc, p.id desc";
            default -> throw new IllegalArgumentException("지원하지 않는 정렬입니다.");
        };
        String sql = "select p.* from products p where p.deleted=false"
            + (brandId == null ? "" : " and p.brand_id=:brand") + " order by " + order + " limit :size offset :offset";
        Query query = entityManager.createNativeQuery(sql, ProductJpaEntity.class)
            .setParameter("size", size)
            .setParameter("offset", (long) page * size);
        if (brandId != null) {
            query.setParameter("brand", brandId);
        }
        List<?> rows = query.getResultList();
        return rows.stream().map(row -> toDomain((ProductJpaEntity) row)).toList();
    }

    private Product toDomain(ProductJpaEntity entity) {
        return Product.restore(new ProductId(entity.getId()), new BrandId(entity.getBrandId()), entity.getName(),
            new Money(entity.getPrice()), new Stock(entity.getStock()), entity.isDeleted());
    }
}
