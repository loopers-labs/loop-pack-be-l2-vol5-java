package com.loopers.application.mall.query;

import com.loopers.application.common.PageResult;
import java.util.Optional;

// 상품 조회 전용 DAO
public interface ProductQueryDao {
    PageResult<ProductSummaryView> findProducts(ProductCriteria criteria);

    PageResult<AdminProductView> findAdminProducts(ProductCriteria criteria);

    Optional<ProductDetailView> findProduct(long productId);

    Optional<AdminProductView> findAdminProduct(long productId);
}
