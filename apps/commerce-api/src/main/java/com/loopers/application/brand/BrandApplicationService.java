package com.loopers.application.brand;

import com.loopers.application.brand.port.BrandRepository;
import com.loopers.application.product.port.ProductRepository;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandId;
import com.loopers.domain.product.Product;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BrandApplicationService {
    private final BrandRepository brandRepository;
    private final ProductRepository productRepository;

    public BrandApplicationService(BrandRepository brandRepository, ProductRepository productRepository) {
        this.brandRepository = brandRepository;
        this.productRepository = productRepository;
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
        // 잠금 순서: 브랜드 → 상품 ID 오름차순. 상품 생성도 브랜드 행을 먼저 잠그므로 삭제 중 같은 브랜드 상품이 추가되지 않는다.
        Brand brand = brandRepository.findByIdForUpdate(new BrandId(id)).orElseThrow(BrandNotFoundException::new);
        if (brand.isDeleted()) {
            return;
        }
        for (Product product : productRepository.findActiveByBrandIdForUpdate(brand.getId())) {
            product.delete();
            productRepository.save(product);
        }
        brand.delete();
        brandRepository.save(brand);
    }

    @Transactional(readOnly = true)
    public List<AdminBrandResult> list(int page, int size) {
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
