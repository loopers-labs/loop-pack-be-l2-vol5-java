package com.loopers.application.brand;

import com.loopers.application.brand.port.BrandRepository;
import com.loopers.domain.brand.BrandId;
import com.loopers.domain.brand.Brand;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BrandApplicationService {
    private final BrandRepository brandRepository;

    private final com.loopers.application.product.port.ProductRepository products;

    public BrandApplicationService(BrandRepository brandRepository,
        com.loopers.application.product.port.ProductRepository products) {
        this.products = products;
        this.brandRepository = brandRepository;
    }

    @Transactional
    public BrandResult create(String name) {
        return BrandResult.from(brandRepository.save(Brand.create(name)));
    }

    @Transactional
    public BrandResult change(long id, String name) {
        Brand brand = brandRepository.findByIdForUpdate(new BrandId(id)).orElseThrow(BrandNotFoundException::new);
        brand.changeName(name);
        return BrandResult.from(brandRepository.save(brand));
    }

    @Transactional
    public void delete(long id) {
        Brand brand = brandRepository.findByIdForUpdate(new BrandId(id)).orElseThrow(BrandNotFoundException::new);
        if (brand.isDeleted()) { return; }
        if (products.existsActiveByBrandId(brand.getId())) {
            throw new com.loopers.domain.common.RuleViolationException("미삭제 상품이 연결된 브랜드는 삭제할 수 없습니다.");
        }
        brand.delete();
        brandRepository.save(brand);
    }

    @Transactional(readOnly = true)
    public java.util.List<AdminBrandResult> list(int page, int size) {
        return brandRepository.findPage(page, size).stream().map(AdminBrandResult::from).toList();
    }

    @Transactional(readOnly = true)
    public AdminBrandResult getAdminBrand(long id) {
        return AdminBrandResult.from(brandRepository.findById(new BrandId(id)).orElseThrow(BrandNotFoundException::new));
    }

    @Transactional(readOnly = true)
    public BrandResult getBrand(BrandId id) {
        Brand brand = brandRepository.findById(id)
            .filter(found -> !found.isDeleted())
            .orElseThrow(BrandNotFoundException::new);
        return BrandResult.from(brand);
    }
}
