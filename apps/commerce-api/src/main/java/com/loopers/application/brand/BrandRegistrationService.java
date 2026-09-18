package com.loopers.application.brand;

import com.loopers.application.user.AdminAuthorization;
import com.loopers.domain.user.UserRole;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BrandRegistrationService {

    private final BrandRepository brandRepository;

    public BrandRegistrationService(BrandRepository brandRepository) {
        this.brandRepository = brandRepository;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AdminBrandInfo register(UserRole requester, String name) {
        AdminAuthorization.requireAdmin(requester);
        Brand brand = brandRepository.save(new Brand(name));
        return AdminBrandInfo.from(brand);
    }
}
