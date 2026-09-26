package com.loopers.product.infrastructure;

import com.loopers.product.domain.Product;
import com.loopers.product.domain.ProductRepository;
import com.loopers.product.domain.ProductSort;
import jakarta.persistence.EntityManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class ProductRepositoryAdapter implements ProductRepository {

    private final ProductJpaRepository jpaRepository;
    private final EntityManager entityManager;

    public ProductRepositoryAdapter(
        ProductJpaRepository jpaRepository,
        EntityManager entityManager
    ) {
        this.jpaRepository = jpaRepository;
        this.entityManager = entityManager;
    }

    @Override
    public Product save(Product product) {
        if (product.getId() == 0L) {
            entityManager.persist(product);
            return product;
        }
        return jpaRepository.save(product);
    }

    @Override
    public Optional<Product> findById(Long id) {
        return jpaRepository.findById(id);
    }

    @Override
    public List<Product> findAllByBrandId(Long brandId) {
        return jpaRepository.findAllByBrandId(brandId);
    }

    @Override
    public List<Product> findAllByBrandIdAndName(Long brandId, String name) {
        return jpaRepository.findAllByBrandIdAndName(brandId, name);
    }

    @Override
    public List<Product> findAll(Long brandId, int page, int size) {
        Sort sort = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id"));
        PageRequest pageRequest = PageRequest.of(page, size, sort);
        if (brandId == null) {
            return jpaRepository.findAll(pageRequest).getContent();
        }
        return jpaRepository.findAllByBrandId(brandId, pageRequest);
    }

    @Override
    public List<Product> findCustomerProducts(
        Long brandId,
        ProductSort sort,
        int page,
        int size
    ) {
        Pageable pageable = PageRequest.of(page, size);
        return switch (sort) {
            case LATEST -> jpaRepository.findCustomerProductsByLatest(brandId, pageable);
            case PRICE_ASC -> jpaRepository.findCustomerProductsByPrice(brandId, pageable);
            case LIKES_DESC -> jpaRepository.findCustomerProductsByLikes(brandId, pageable);
        };
    }

    @Override
    public long countAll(Long brandId) {
        if (brandId == null) {
            return jpaRepository.count();
        }
        return jpaRepository.countByBrandId(brandId);
    }

    @Override
    public long countCustomerProducts(Long brandId) {
        return jpaRepository.countCustomerProducts(brandId);
    }
}
