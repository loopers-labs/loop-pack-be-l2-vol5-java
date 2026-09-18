package com.loopers.application.product;

import com.loopers.domain.common.PageResult;
import com.loopers.domain.product.ProductQueryRepository;
import com.loopers.domain.product.ProductSort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ProductQueryService {

    private final ProductQueryRepository repository;

    public ProductQueryService(ProductQueryRepository repository) {
        this.repository = repository;
    }

    public ProductInfo getDetail(long productId) {
        return repository.findDetail(productId, false).map(ProductInfo::from)
            .orElseThrow(() -> new ProductQueryException(ProductQueryException.Reason.PRODUCT_NOT_FOUND));
    }

    public PageResult<ProductInfo> getList(Long brandId, int page, int size, ProductSort sort) {
        return repository.findPage(brandId, page, size, sort, false).map(ProductInfo::from);
    }
}
