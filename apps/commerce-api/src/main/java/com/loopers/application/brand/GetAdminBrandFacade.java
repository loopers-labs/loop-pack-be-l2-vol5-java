package com.loopers.application.brand;

import com.loopers.domain.brand.BrandRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GetAdminBrandFacade {
    private final BrandRepository repository;
    public BrandInfo admin(long id) {
        return BrandInfo.from(repository.findById(id).orElseThrow(() -> new CoreException(ErrorType.BRAND_NOT_FOUND)));
    }
    public Page<BrandInfo> list(int page, int size) {
        if (page < 0 || size < 1 || size > 100) { throw new CoreException(ErrorType.INVALID_REQUEST); }
        return repository.findAll(PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "id"))).map(BrandInfo::from);
    }
}
