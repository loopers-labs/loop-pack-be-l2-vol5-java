package com.loopers.application.mall.service;

import com.loopers.application.mall.command.BrandCommand;
import com.loopers.application.mall.event.BrandProductsChangedEvent;
import com.loopers.application.mall.result.BrandResult;
import com.loopers.application.mall.usecase.CreateBrandUseCase;
import com.loopers.application.mall.usecase.DeleteBrandUseCase;
import com.loopers.application.mall.usecase.UpdateBrandUseCase;
import com.loopers.application.support.error.ApplicationErrorCode;
import com.loopers.application.support.error.ApplicationException;
import com.loopers.domain.mall.model.Brand;
import com.loopers.domain.mall.repository.BrandRepository;
import com.loopers.domain.mall.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
// 브랜드 생성·수정·삭제 유스케이스 구현
public class BrandService implements CreateBrandUseCase, UpdateBrandUseCase, DeleteBrandUseCase {
    private final BrandRepository brandRepository;
    private final ProductRepository productRepository;
    private final ApplicationEventPublisher eventPublisher;

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
        Brand saved = brandRepository.save(brand);
        eventPublisher.publishEvent(new BrandProductsChangedEvent(saved.getId()));
        return BrandResult.from(saved);
    }

    // 브랜드를 삭제하고 소속 미삭제 상품 전체를 한 번에 삭제
    @Override
    @Transactional
    public void execute(BrandCommand.Delete command) {
        Brand brand = brandRepository.findByIdForUpdate(command.brandId())
            .orElseThrow(() -> new ApplicationException(ApplicationErrorCode.BRAND_NOT_FOUND));
        brand.delete();
        brandRepository.save(brand);
        productRepository.deleteAllByBrandId(command.brandId());
        eventPublisher.publishEvent(new BrandProductsChangedEvent(command.brandId()));
    }

    private Brand findBrand(long brandId) {
        return brandRepository.findById(brandId)
            .orElseThrow(() -> new ApplicationException(ApplicationErrorCode.BRAND_NOT_FOUND));
    }
}
