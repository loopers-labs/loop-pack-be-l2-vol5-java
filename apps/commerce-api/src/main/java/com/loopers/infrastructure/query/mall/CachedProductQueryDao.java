package com.loopers.infrastructure.query.mall;

import com.loopers.application.common.PageResult;
import com.loopers.application.mall.query.AdminProductView;
import com.loopers.application.mall.query.ProductCriteria;
import com.loopers.application.mall.query.ProductDetailView;
import com.loopers.application.mall.query.ProductQueryDao;
import com.loopers.application.mall.query.ProductSummaryView;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

// DB 구현의 트랜잭션 프록시를 보존하고 캐시 적중에는 새 트랜잭션을 만들지 않는다.
@Primary
@Component
public class CachedProductQueryDao implements ProductQueryDao {
    private final ProductQueryDao delegate;
    private final ProductDetailCache cache;

    public CachedProductQueryDao(@Qualifier("queryDslProductQueryDao") ProductQueryDao delegate,
                                 ProductDetailCache cache) {
        this.delegate = delegate;
        this.cache = cache;
    }

    @Override
    public PageResult<ProductSummaryView> findProducts(ProductCriteria criteria) {
        return delegate.findProducts(criteria);
    }

    @Override
    public PageResult<AdminProductView> findAdminProducts(ProductCriteria criteria) {
        return delegate.findAdminProducts(criteria);
    }

    @Override
    public Optional<ProductDetailView> findProduct(long productId) {
        return cache.find(productId, () -> delegate.findProduct(productId));
    }

    @Override
    public Optional<AdminProductView> findAdminProduct(long productId) {
        return delegate.findAdminProduct(productId);
    }
}
