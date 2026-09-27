package com.loopers.infrastructure.product;

import com.loopers.domain.product.ProductModel;
import com.loopers.domain.product.ProductRepository;
import com.loopers.support.paging.PageQuery;
import com.loopers.support.paging.PageResult;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static com.loopers.domain.product.QProductModel.productModel;

@RequiredArgsConstructor
@Component
public class ProductRepositoryImpl implements ProductRepository {
    private final ProductJpaRepository productJpaRepository;
    private final JPAQueryFactory queryFactory;

    @Override
    public ProductModel save(ProductModel product) {
        return productJpaRepository.save(product);
    }

    @Override
    public Optional<ProductModel> find(Long id) {
        if (id == null) {
            return Optional.empty();
        }
        return productJpaRepository.findById(id);
    }

    @Override
    public List<ProductModel> findByIds(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return productJpaRepository.findAllById(ids);
    }

    @Override
    public boolean existsActiveByBrandId(Long brandId) {
        return productJpaRepository.existsByBrandIdAndDeletedAtIsNull(brandId);
    }

    @Override
    public PageResult<ProductModel> findPage(PageQuery query) {
        List<ProductModel> items = queryFactory.selectFrom(productModel)
            .orderBy(productModel.createdAt.desc(), productModel.id.desc())
            .offset(query.offset())
            .limit(query.size())
            .fetch();
        long total = productJpaRepository.count();
        return PageResult.of(items, query, total);
    }
}
