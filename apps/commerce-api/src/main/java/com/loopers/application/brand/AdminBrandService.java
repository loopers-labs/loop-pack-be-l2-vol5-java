package com.loopers.application.brand;

import com.loopers.application.user.AdminAuthorization;
import com.loopers.domain.user.UserRole;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandDeletionService;
import com.loopers.domain.brand.BrandQueryRepository;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.common.PageResult;
import com.loopers.domain.product.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

@Service
public class AdminBrandService {
    private final BrandRepository brands;
    private final BrandQueryRepository queries;
    private final BrandDeletionService deletion;

    public AdminBrandService(BrandRepository brands, BrandQueryRepository queries,
                             ProductRepository products) {
        this.brands = brands;
        this.queries = queries;
        this.deletion = new BrandDeletionService(brands, products);
    }

    @Transactional(readOnly = true)
    public PageResult<AdminBrandInfo> list(UserRole requester, int page, int size) {
        AdminAuthorization.requireAdmin(requester);
        return queries.findPage(page, size).map(AdminBrandInfo::from);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AdminBrandInfo rename(UserRole requester, long brandId, String name) {
        AdminAuthorization.requireAdmin(requester);
        Brand brand = lock(brandId);
        brand.rename(name);
        return AdminBrandInfo.from(brands.save(brand));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void delete(UserRole requester, long brandId) {
        AdminAuthorization.requireAdmin(requester);
        lock(brandId);
        Brand brand = deletion.delete(brandId, ZonedDateTime.now().truncatedTo(ChronoUnit.MICROS));
        brands.save(brand);
    }

    private Brand lock(long id) {
        return brands.lockById(id).orElseThrow(() -> new BrandQueryException(BrandQueryException.Reason.BRAND_NOT_FOUND));
    }
}
