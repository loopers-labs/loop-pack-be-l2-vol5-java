package com.loopers.application.product;

import com.loopers.application.user.AdminAuthorization;
import com.loopers.domain.user.UserRole;
import com.loopers.domain.common.PageResult;
import com.loopers.domain.product.ProductQueryRepository;
import com.loopers.domain.product.ProductSort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AdminProductQueryService {

    private final ProductQueryRepository repository;

    public AdminProductQueryService(ProductQueryRepository repository) {
        this.repository = repository;
    }

    public AdminProductQueryInfo getDetail(UserRole requester, long productId) {
        AdminAuthorization.requireAdmin(requester);
        return repository.findDetail(productId, true).map(AdminProductQueryInfo::from)
            .orElseThrow(() -> new ProductQueryException(ProductQueryException.Reason.PRODUCT_NOT_FOUND));
    }

    public PageResult<AdminProductQueryInfo> getList(UserRole requester, Long brandId, int page, int size, ProductSort sort) {
        AdminAuthorization.requireAdmin(requester);
        return repository.findPage(brandId, page, size, sort, true).map(AdminProductQueryInfo::from);
    }
}
