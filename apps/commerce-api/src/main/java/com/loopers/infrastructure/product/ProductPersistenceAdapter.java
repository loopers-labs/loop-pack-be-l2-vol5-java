package com.loopers.infrastructure.product;

import com.loopers.application.product.port.ProductRepository;
import com.loopers.domain.brand.BrandId;
import com.loopers.domain.common.Money;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductId;
import com.loopers.domain.product.Stock;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.util.Optional;

@Repository
@Transactional(readOnly = true)
public class ProductPersistenceAdapter implements ProductRepository {
    private final ProductJpaRepository repository;

    private final jakarta.persistence.EntityManager entityManager;
    public ProductPersistenceAdapter(ProductJpaRepository repository, jakarta.persistence.EntityManager entityManager) {
        this.repository = repository;
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
            entity = repository.findById(product.getId().value()).orElseThrow();
        }
        entity.update(product.getName(), product.getPrice().value(), product.getStock().value(), product.isDeleted());
        return toDomain(repository.save(entity));
    }

    @Override
    public Optional<Product> findById(ProductId id) { return repository.findById(id.value()).map(this::toDomain); }

    @Override
    @Transactional
    public Optional<Product> findByIdForUpdate(ProductId id) { return repository.findForUpdate(id.value()).map(this::toDomain); }

    @Override
    public boolean existsActiveByBrandId(BrandId id) { return repository.existsByBrandIdAndDeletedFalse(id.value()); }

    @Override
    public java.util.List<Product> findPage(int page, int size) {
        return repository.findAll(org.springframework.data.domain.PageRequest.of(page, size,
            org.springframework.data.domain.Sort.by("id").descending())).stream().map(this::toDomain).toList();
    }

    @Override
    public java.util.List<Product> findAllByIds(java.util.Collection<ProductId> ids) {
        return repository.findAllById(ids.stream().map(ProductId::value).toList()).stream().map(this::toDomain).toList();
    }

    @Override
    public java.util.List<Product> search(Long brandId, int page, int size, String sort) {
        String order = switch (sort) {
            case "latest" -> "p.created_at desc, p.id desc";
            case "price_asc" -> "p.price asc, p.id desc";
            case "likes_desc" -> "(select count(*) from product_likes l where l.product_id=p.id) desc, p.id desc";
            default -> throw new IllegalArgumentException("지원하지 않는 정렬입니다.");
        };
        String sql = "select p.* from products p where p.deleted=false"
            + (brandId == null ? "" : " and p.brand_id=:brand") + " order by " + order + " limit :size offset :offset";
        var query = entityManager.createNativeQuery(sql, ProductJpaEntity.class)
            .setParameter("size", size).setParameter("offset", (long) page * size);
        if (brandId != null) { query.setParameter("brand", brandId); }
        java.util.List<?> rows = query.getResultList();
        return rows.stream().map(row -> toDomain((ProductJpaEntity) row)).toList();
    }

    private Product toDomain(ProductJpaEntity entity) {
        return Product.restore(new ProductId(entity.getId()), new BrandId(entity.getBrandId()), entity.getName(),
            new Money(entity.getPrice()), new Stock(entity.getStock()), entity.isDeleted());
    }
}
