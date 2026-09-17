package com.loopers.application.brand;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandDeletionPolicy;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@RequiredArgsConstructor
@Component
public class BrandFacade {

    private final BrandRepository brandRepository;
    private final BrandDeletionPolicy brandDeletionPolicy;

    @Transactional(readOnly = true)
    public BrandInfo getDetail(Long brandId) {
        Brand brand = findBrandById(brandId);
        return BrandInfo.from(brand);
    }

    @Transactional(readOnly = true)
    public List<BrandInfo> getList(BrandListStatus status) {
        List<Brand> brands = switch (status) {
            case ACTIVE -> brandRepository.findAllActive();
            case DELETED -> brandRepository.findAllDeleted();
            case ALL -> brandRepository.findAll();
        };
        return brands.stream().map(BrandInfo::from).toList();
    }

    @Transactional
    public BrandInfo update(Long brandId, String name) {
        Brand brand = findBrandById(brandId);
        if (brand.getDeletedAt() != null) {
            throw new CoreException(ErrorType.NOT_FOUND, "브랜드를 찾을 수 없습니다.");
        }

        String validName = Brand.validateName(name);
        if (brandRepository.existsByNameAndIdNot(validName, brandId)) {
            throw new CoreException(ErrorType.CONFLICT, "이미 등록된 브랜드 이름입니다.");
        }

        brand.rename(validName);
        Brand savedBrand = brandRepository.save(brand);
        return BrandInfo.from(savedBrand);
    }

    @Transactional
    public BrandInfo register(String name) {
        Brand brand = Brand.create(name);
        if (brandRepository.existsByName(brand.getName())) {
            throw new CoreException(ErrorType.CONFLICT, "이미 등록된 브랜드 이름입니다.");
        }

        Brand savedBrand = brandRepository.save(brand);
        return BrandInfo.from(savedBrand);
    }

    @Transactional
    public void delete(Long brandId) {
        brandDeletionPolicy.delete(brandId);
    }

    private Brand findBrandById(Long brandId) {
        return brandRepository.findById(brandId)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "브랜드를 찾을 수 없습니다."));
    }
}
