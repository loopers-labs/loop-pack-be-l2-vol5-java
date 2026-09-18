package com.loopers.application.brand;

import com.loopers.application.user.AdminAuthorization;
import com.loopers.domain.user.UserRole;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AdminBrandQueryService {

    private final BrandRepository brandRepository;

    public AdminBrandQueryService(BrandRepository brandRepository) {
        this.brandRepository = brandRepository;
    }

    /**
     * 관리자 권한을 확인한 뒤 삭제 여부와 관계없이 브랜드 상세 정보를 조회한다.
     */
    public AdminBrandInfo getDetail(UserRole requester, long brandId) {
        AdminAuthorization.requireAdmin(requester);
        Brand brand = brandRepository.findById(brandId)
            .orElseThrow(() -> new BrandQueryException(BrandQueryException.Reason.BRAND_NOT_FOUND));
        return AdminBrandInfo.from(brand);
    }
}
