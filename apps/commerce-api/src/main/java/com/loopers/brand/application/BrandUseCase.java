package com.loopers.brand.application;

import com.loopers.brand.domain.Brand;
import com.loopers.brand.domain.BrandDeletionValidator;
import com.loopers.brand.domain.BrandNameValidator;
import com.loopers.brand.domain.BrandRepository;
import com.loopers.product.domain.ProductRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorCode;
import com.loopers.support.page.PageResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class BrandUseCase {

    private final BrandRepository brandRepository;
    private final BrandNameValidator nameValidator;
    private final BrandDeletionValidator deletionValidator;

    public BrandUseCase(BrandRepository brandRepository, ProductRepository productRepository) {
        this.brandRepository = brandRepository;
        this.nameValidator = new BrandNameValidator(brandRepository);
        this.deletionValidator = new BrandDeletionValidator(productRepository);
    }

    @Transactional
    public Brand create(String name) {
        Brand brand = new Brand(name);
        nameValidator.validateNotDuplicated(brand.getName());
        return brandRepository.save(brand);
    }

    @Transactional
    public Brand update(Long brandId, String name) {
        Brand brand = findRequired(brandId);
        if (brand.isDeleted()) {
            throw new CoreException(ErrorCode.BRAND_NOT_FOUND);
        }
        Brand candidate = new Brand(name);
        nameValidator.validateNotDuplicated(candidate.getName(), brandId);
        brand.update(candidate.getName());
        return brandRepository.save(brand);
    }

    @Transactional(readOnly = true)
    public Brand find(Long brandId) {
        return findRequired(brandId);
    }

    @Transactional(readOnly = true)
    public Brand findActive(Long brandId) {
        Brand brand = findRequired(brandId);
        if (brand.isDeleted()) {
            throw new CoreException(ErrorCode.BRAND_NOT_FOUND);
        }
        return brand;
    }

    @Transactional
    public void delete(Long brandId) {
        Brand brand = findRequired(brandId);
        if (brand.isDeleted()) {
            throw new CoreException(ErrorCode.BRAND_NOT_FOUND);
        }
        deletionValidator.validateDeletable(brand);
        brand.delete();
        brandRepository.save(brand);
    }

    @Transactional(readOnly = true)
    public List<Brand> findAll(int page, int size) {
        return brandRepository.findAll(page, size);
    }

    @Transactional(readOnly = true)
    public PageResult<Brand> findPage(int page, int size) {
        return new PageResult<>(brandRepository.findAll(page, size), page, size, brandRepository.countAll());
    }

    private Brand findRequired(Long brandId) {
        return brandRepository.findById(brandId)
            .orElseThrow(() -> new CoreException(ErrorCode.BRAND_NOT_FOUND));
    }
}
