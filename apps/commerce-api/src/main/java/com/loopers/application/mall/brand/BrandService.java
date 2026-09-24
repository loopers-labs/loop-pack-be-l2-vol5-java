package com.loopers.application.mall.brand;

import com.loopers.application.support.error.ApplicationErrorCode;
import com.loopers.application.support.error.ApplicationException;
import com.loopers.domain.mall.brand.Brand;
import com.loopers.domain.mall.brand.BrandRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
// 브랜드 생성·수정·삭제 유스케이스 구현
public class BrandService implements CreateBrandUseCase, UpdateBrandUseCase, DeleteBrandUseCase {
    private final BrandRepository brandRepository;

    // 브랜드 생성
    @Override
    @Transactional
    public BrandResult execute(BrandCommand.Create command) {
        return BrandResult.from(brandRepository.save(Brand.create(command.name(), command.description())));
    }

    // 브랜드 수정
    @Override
    @Transactional
    public BrandResult execute(BrandCommand.Update command) {
        Brand brand = findBrand(command.brandId());
        brand.update(command.name(), command.description());
        return BrandResult.from(brandRepository.save(brand));
    }

    // 브랜드와 연결된 미삭제 상품 전체를 함께 삭제
    @Override
    @Transactional
    public void execute(BrandCommand.Delete command) {
        Brand brand = brandRepository.findForDeletion(command.brandId())
            .orElseThrow(() -> new ApplicationException(ApplicationErrorCode.BRAND_NOT_FOUND));
        brand.delete();
        brandRepository.save(brand);
    }

    private Brand findBrand(long brandId) {
        return brandRepository.findById(brandId)
            .orElseThrow(() -> new ApplicationException(ApplicationErrorCode.BRAND_NOT_FOUND));
    }
}
