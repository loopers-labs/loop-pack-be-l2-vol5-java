package com.loopers.application.product;

import com.loopers.application.product.query.ProductQuery;
import com.loopers.application.product.query.ProductView;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.util.Set;

@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GetProductFacade {
    private final ProductQuery query;
    public ProductView get(long id) {
        return query.find(id).orElseThrow(() -> new CoreException(ErrorType.PRODUCT_NOT_FOUND));
    }
    public Page<ProductView> list(Long brandId, int page, int size, String sort) {
        if (page < 0 || size < 1 || size > 100 || (brandId != null && brandId <= 0)
            || !Set.of("latest", "price_asc", "likes_desc").contains(sort)) {
            throw new CoreException(ErrorType.INVALID_REQUEST);
        }
        return query.list(brandId, PageRequest.of(page, size), sort);
    }
}
