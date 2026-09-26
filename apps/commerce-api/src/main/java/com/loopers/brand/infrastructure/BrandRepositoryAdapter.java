package com.loopers.brand.infrastructure;

import com.loopers.brand.domain.Brand;
import com.loopers.brand.domain.BrandRepository;
import jakarta.persistence.EntityManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class BrandRepositoryAdapter implements BrandRepository {

    private final BrandJpaRepository jpaRepository;
    private final EntityManager entityManager;

    public BrandRepositoryAdapter(BrandJpaRepository jpaRepository, EntityManager entityManager) {
        this.jpaRepository = jpaRepository;
        this.entityManager = entityManager;
    }

    @Override
    public Brand save(Brand brand) {
        if (brand.getId() == 0L) {
            entityManager.persist(brand);
            return brand;
        }
        return jpaRepository.save(brand);
    }

    @Override
    public Optional<Brand> findById(Long id) {
        return jpaRepository.findById(id);
    }

    @Override
    public List<Brand> findAllByName(String name) {
        return jpaRepository.findAllByName(name);
    }

    @Override
    public List<Brand> findAll(int page, int size) {
        Sort sort = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id"));
        return jpaRepository.findAll(PageRequest.of(page, size, sort)).getContent();
    }

    @Override
    public long countAll() {
        return jpaRepository.count();
    }
}
