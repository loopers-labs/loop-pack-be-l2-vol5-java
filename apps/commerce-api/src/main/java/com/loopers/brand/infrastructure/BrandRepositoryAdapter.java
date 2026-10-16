package com.loopers.brand.infrastructure;

import com.loopers.brand.domain.Brand;
import com.loopers.brand.domain.BrandRepository;
import jakarta.persistence.EntityManager;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.DatabaseConstraintErrors;
import com.loopers.support.error.ErrorCode;
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
        try {
            Brand saved;
            if (brand.getId() == 0L) {
                entityManager.persist(brand);
                saved = brand;
            } else {
                saved = jpaRepository.save(brand);
            }
            entityManager.flush();
            return saved;
        } catch (RuntimeException failure) {
            if (DatabaseConstraintErrors.isDuplicateKey(failure, "uk_brand_active_name")) {
                throw new CoreException(ErrorCode.DUPLICATE_BRAND_NAME);
            }
            throw failure;
        }
    }

    @Override
    public List<Brand> findAllByIds(List<Long> ids) {
        return ids.isEmpty() ? List.of() : jpaRepository.findAllById(ids);
    }

    @Override
    public Optional<Brand> findById(Long id) {
        return jpaRepository.findById(id);
    }

    @Override
    public Optional<Brand> findForProductCreate(Long id) {
        return jpaRepository.findForProductCreate(id);
    }

    @Override
    public Optional<Brand> findForWrite(Long id) {
        return jpaRepository.findForWrite(id);
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

    @Override
    public List<Brand> findActive(int page, int size) {
        Sort sort = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id"));
        return jpaRepository.findByDeletedAtIsNull(PageRequest.of(page, size, sort));
    }

    @Override
    public long countActive() {
        return jpaRepository.countByDeletedAtIsNull();
    }

}
